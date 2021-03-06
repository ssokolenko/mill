package mill.modules

import java.io.{ByteArrayInputStream, FileOutputStream}
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.attribute.PosixFilePermission
import java.util.jar.{JarEntry, JarFile, JarOutputStream}

import ammonite.ops._
import mill.clientserver.{ClientServer, InputPumper}
import mill.define.Task
import mill.eval.PathRef
import mill.util.{Ctx, Loose}
import mill.util.Ctx.Log
import mill.util.Loose.Agg
import upickle.default.{Reader, Writer}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.reflect.ClassTag


object Jvm {

  def interactiveSubprocess(mainClass: String,
                            classPath: Agg[Path],
                            jvmArgs: Seq[String] = Seq.empty,
                            envArgs: Map[String, String] = Map.empty,
                            mainArgs: Seq[String] = Seq.empty,
                            workingDir: Path = null): Unit = {
    baseInteractiveSubprocess(
      Vector("java") ++
        jvmArgs ++
        Vector("-cp", classPath.mkString(":"), mainClass) ++
        mainArgs,
      envArgs,
      workingDir
    )
  }

  def baseInteractiveSubprocess(commandArgs: Seq[String],
                                envArgs: Map[String, String],
                                workingDir: Path) = {
    val builder = new java.lang.ProcessBuilder()

    for ((k, v) <- envArgs){
      if (v != null) builder.environment().put(k, v)
      else builder.environment().remove(k)
    }
    builder.directory(workingDir.toIO)

    val process = if (System.in.isInstanceOf[ByteArrayInputStream]){

      val process = builder
        .command(commandArgs:_*)
        .start()

      val sources = Seq(
        process.getInputStream -> System.out,
        process.getErrorStream -> System.err,
        System.in -> process.getOutputStream
      )

      for((std, dest) <- sources){
        new Thread(new InputPumper(std, dest, false)).start()
      }
      process
    }else{
      builder
        .command(commandArgs:_*)
        .inheritIO()
        .start()
    }

    val exitCode = process.waitFor()
    if (exitCode == 0) ()
    else throw InteractiveShelloutException()
  }

  def runLocal(mainClass: String,
               classPath: Agg[Path],
               mainArgs: Seq[String] = Seq.empty)
              (implicit ctx: Ctx): Unit = {
    inprocess(classPath, classLoaderOverrideSbtTesting = false, cl => {
      getMainMethod(mainClass, cl).invoke(null, mainArgs.toArray)
    })
  }

  private def getMainMethod(mainClassName: String, cl: ClassLoader) = {
    val mainClass = cl.loadClass(mainClassName)
    val method = mainClass.getMethod("main", classOf[Array[String]])
    // jvm allows the actual main class to be non-public and to run a method in the non-public class,
    //  we need to make it accessible
    method.setAccessible(true)
    val modifiers = method.getModifiers
    if (!Modifier.isPublic(modifiers))
      throw new NoSuchMethodException(mainClassName + ".main is not public")
    if (!Modifier.isStatic(modifiers))
      throw new NoSuchMethodException(mainClassName + ".main is not static")
    method
  }



  def inprocess[T](classPath: Agg[Path],
                   classLoaderOverrideSbtTesting: Boolean,
                   body: ClassLoader => T): T = {
    val cl = if (classLoaderOverrideSbtTesting) {
      val outerClassLoader = getClass.getClassLoader
      new URLClassLoader(classPath.map(_.toIO.toURI.toURL).toArray, null){
        override def findClass(name: String) = {
          if (name.startsWith("sbt.testing.")){
            outerClassLoader.loadClass(name)
          }else{
            super.findClass(name)
          }
        }
      }
    } else {
      new URLClassLoader(classPath.map(_.toIO.toURI.toURL).toArray, null)
    }
    val oldCl = Thread.currentThread().getContextClassLoader
    Thread.currentThread().setContextClassLoader(cl)
    try {
      body(cl)
    }finally{
      Thread.currentThread().setContextClassLoader(oldCl)
      cl.close()
    }
  }

  def subprocess(mainClass: String,
                 classPath: Agg[Path],
                 jvmArgs: Seq[String] = Seq.empty,
                 envArgs: Map[String, String] = Map.empty,
                 mainArgs: Seq[String] = Seq.empty,
                 workingDir: Path = null)
                (implicit ctx: Ctx) = {

    val commandArgs =
      Vector("java") ++
      jvmArgs ++
      Vector("-cp", classPath.mkString(":"), mainClass) ++
      mainArgs

    val workingDir1 = Option(workingDir).getOrElse(ctx.dest)
    mkdir(workingDir1)
    val builder =
      new java.lang.ProcessBuilder()
        .directory(workingDir1.toIO)
        .command(commandArgs:_*)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)

    for((k, v) <- envArgs) builder.environment().put(k, v)
    val proc = builder.start()
    val stdout = proc.getInputStream
    val stderr = proc.getErrorStream
    val sources = Seq(
      (stdout, Left(_: Bytes), ctx.log.outputStream),
      (stderr, Right(_: Bytes),ctx.log.errorStream )
    )
    val chunks = mutable.Buffer.empty[Either[Bytes, Bytes]]
    while(
    // Process.isAlive doesn't exist on JDK 7 =/
      util.Try(proc.exitValue).isFailure ||
        stdout.available() > 0 ||
        stderr.available() > 0
    ){
      var readSomething = false
      for ((subStream, wrapper, parentStream) <- sources){
        while (subStream.available() > 0){
          readSomething = true
          val array = new Array[Byte](subStream.available())
          val actuallyRead = subStream.read(array)
          chunks.append(wrapper(new ammonite.ops.Bytes(array)))
          parentStream.write(array, 0, actuallyRead)
        }
      }
      // if we did not read anything sleep briefly to avoid spinning
      if(!readSomething)
        Thread.sleep(2)
    }

    if (proc.exitValue() != 0) throw new InteractiveShelloutException()
    else ammonite.ops.CommandResult(proc.exitValue(), chunks)
  }

  private def createManifest(mainClass: Option[String]) = {
    val m = new java.util.jar.Manifest()
    m.getMainAttributes.put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0")
    m.getMainAttributes.putValue( "Created-By", "Scala mill" )
    mainClass.foreach(
      m.getMainAttributes.put(java.util.jar.Attributes.Name.MAIN_CLASS, _)
    )
    m
  }

  def createJar(inputPaths: Agg[Path], mainClass: Option[String] = None)
               (implicit ctx: Ctx.Dest): PathRef = {
    val outputPath = ctx.dest / "out.jar"
    rm(outputPath)

    val seen = mutable.Set.empty[RelPath]
    seen.add("META-INF" / "MANIFEST.MF")
    val jar = new JarOutputStream(
      new FileOutputStream(outputPath.toIO),
      createManifest(mainClass)
    )

    try{
      assert(inputPaths.forall(exists(_)))
      for{
        p <- inputPaths
        (file, mapping) <-
          if (p.isFile) Iterator(p -> empty/p.last)
          else ls.rec(p).filter(_.isFile).map(sub => sub -> sub.relativeTo(p))
        if !seen(mapping)
      } {
        seen.add(mapping)
        val entry = new JarEntry(mapping.toString)
        entry.setTime(file.mtime.toMillis)
        jar.putNextEntry(entry)
        jar.write(read.bytes(file))
        jar.closeEntry()
      }
    } finally {
      jar.close()
    }

    PathRef(outputPath)
  }

  def createAssembly(inputPaths: Agg[Path],
                     mainClass: Option[String] = None,
                     prependShellScript: String = "")
                    (implicit ctx: Ctx.Dest): PathRef = {
    val outputPath = ctx.dest / "out.jar"
    rm(outputPath)

    if(inputPaths.nonEmpty) {

      val output = new FileOutputStream(outputPath.toIO)

      // Prepend shell script and make it executable
      if (prependShellScript.nonEmpty) {
        output.write((prependShellScript + "\n").getBytes)
        val perms = java.nio.file.Files.getPosixFilePermissions(outputPath.toNIO)
        perms.add(PosixFilePermission.GROUP_EXECUTE)
        perms.add(PosixFilePermission.OWNER_EXECUTE)
        perms.add(PosixFilePermission.OTHERS_EXECUTE)
        java.nio.file.Files.setPosixFilePermissions(outputPath.toNIO, perms)
      }

      val jar = new JarOutputStream(
        output,
        createManifest(mainClass)
      )

      val seen = mutable.Set("META-INF/MANIFEST.MF")
      try{


        for{
          p <- inputPaths
          if exists(p)
          (file, mapping) <-
            if (p.isFile) {
              val jf = new JarFile(p.toIO)
              import collection.JavaConverters._
              for(entry <- jf.entries().asScala if !entry.isDirectory) yield {
                read.bytes(jf.getInputStream(entry)) -> entry.getName
              }
            }
            else {
              ls.rec(p).iterator
                .filter(_.isFile)
                .map(sub => read.bytes(sub) -> sub.relativeTo(p).toString)
            }
          if !seen(mapping)
        } {
          seen.add(mapping)
          val entry = new JarEntry(mapping.toString)
          jar.putNextEntry(entry)
          jar.write(file)
          jar.closeEntry()
        }
      } finally {
        jar.close()
        output.close()
      }

    }
    PathRef(outputPath)
  }
  def launcherShellScript(mainClass: String,
                          classPath: Agg[String],
                          jvmArgs: Seq[String]) = {
    s"""#!/usr/bin/env sh
       |
       |exec java ${jvmArgs.mkString(" ")} $$JAVA_OPTS -cp "${classPath.mkString(":")}" $mainClass "$$@"
     """.stripMargin
  }
  def createLauncher(mainClass: String,
                     classPath: Agg[Path],
                     jvmArgs: Seq[String])
                    (implicit ctx: Ctx.Dest)= {
    val outputPath = ctx.dest / "run"

    write(outputPath, launcherShellScript(mainClass, classPath.map(_.toString), jvmArgs))

    val perms = java.nio.file.Files.getPosixFilePermissions(outputPath.toNIO)
    perms.add(PosixFilePermission.GROUP_EXECUTE)
    perms.add(PosixFilePermission.OWNER_EXECUTE)
    perms.add(PosixFilePermission.OTHERS_EXECUTE)
    java.nio.file.Files.setPosixFilePermissions(outputPath.toNIO, perms)
    PathRef(outputPath)
  }

}
