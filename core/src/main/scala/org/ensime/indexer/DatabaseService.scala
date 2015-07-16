package org.ensime.indexer

import java.io.File
import java.sql.Timestamp

import akka.event.slf4j.SLF4JLogging
import com.jolbox.bonecp.BoneCPDataSource
import org.apache.commons.vfs2.FileObject
import org.ensime.indexer.DatabaseService._
import org.ensime.indexer.IndexService.FqnIndex

import org.ensime.api._

import scala.slick.driver.H2Driver.simple._

import scalaz.std.list._

class DatabaseService(dir: File) extends SLF4JLogging {

  lazy val dao = {
    // MVCC plus connection pooling speeds up the tests ~10%
    val url = "jdbc:h2:file:" + dir.getAbsolutePath + "/db;MVCC=TRUE"
    val driver = "org.h2.Driver"
    //Database.forURL(url, driver = driver)

    // http://jolbox.com/benchmarks.html
    val ds = new BoneCPDataSource()
    ds.setDriverClass(driver)
    ds.setJdbcUrl(url)
    ds.setStatementsCacheSize(50)
    new DatabaseServiceDAO(ds)
  }

  def shutdown(): Unit =
    dao.shutdown.unsafePerformIO()

  if (!dir.exists) {
    log.info("creating the search database")
    dir.mkdirs()
    dao.create.unsafePerformIO()
  }

  // TODO hierarchy
  // TODO reverse lookup table

  // file with last modified time
  def knownFiles(): List[FileCheck] =
    dao.allFiles.unsafePerformIO()

  def removeFiles(files: List[FileObject]): Int =
    dao.removeFiles(files.map(_.getName.getURI)).unsafePerformIO

  def outOfDate(f: FileObject): Boolean =
    dao.outOfDate(f.getName.getURI, f.getContent.getLastModifiedTime).unsafePerformIO()

  def persist(check: FileCheck, symbols: List[FqnSymbol]): Unit =
    dao.persist(check, symbols).unsafePerformIO()

  def find(fqn: String): Option[FqnSymbol] =
    dao.find(fqn).unsafePerformIO()

  def find(fqns: List[FqnIndex]): List[FqnSymbol] = {
    val restrict = fqns.map(_.fqn)
    val results = dao.find(restrict).unsafePerformIO().groupBy(_.fqn)
    restrict.flatMap(results.get(_).map(_.head))
  }

}

object DatabaseService {

  case class FileCheck(id: Option[Int], filename: String, timestamp: Timestamp) {
    def file(implicit vfs: EnsimeVFS) = vfs.vfile(filename)
    def lastModified = timestamp.getTime
    def changed(implicit vfs: EnsimeVFS) = file.getContent.getLastModifiedTime != lastModified
  }
  object FileCheck {
    def apply(f: FileObject): FileCheck = {
      val name = f.getName.getURI
      val ts = new Timestamp(f.getContent.getLastModifiedTime)
      FileCheck(None, name, ts)
    }
  }

  case class FqnSymbol(
      id: Option[Int],
      file: String, // the underlying file
      path: String, // the VFS handle (e.g. classes in jars)
      fqn: String,
      descriptor: Option[String], // for methods
      internal: Option[String], // for fields
      source: Option[String], // VFS
      line: Option[Int],
      offset: Option[Int] = None // future features:
  //    type: ??? --- better than descriptor/internal
  ) {
    // this is just as a helper until we can use more sensible
    // domain objects with slick
    def sourceFileObject(implicit vfs: EnsimeVFS) = source.map(vfs.vfile)

    // legacy: note that we can't distinguish class/trait
    def declAs: DeclaredAs =
      if (descriptor.isDefined) DeclaredAs.Method
      else if (internal.isDefined) DeclaredAs.Field
      else DeclaredAs.Class
  }

}
