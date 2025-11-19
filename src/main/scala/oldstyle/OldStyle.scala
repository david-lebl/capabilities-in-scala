package oldstyle

import java.util.UUID

@main def oldStyleMain = 
  println("Old Scala style of capabilities")
  Library.defaultSystem { implicit _ =>  // scoped DI, just need to add implicit
    given BlobStore = BlboStore.makeDefault // or, flat partially-scoped DI
    UploadsUseCase.uploadSingleFile("123qwe".getBytes)
  }

trait Library:
  def register(name: String): Unit
  def delete(name: String): Unit

object Library:
  def register(name: String)(using l: Library): Unit =
    l.register(name)

  def makeDefault = new Library:
    override def register(name: String): Unit =
      println(s"registered file: $name")

    override def delete(name: String): Unit =
      println(s"deleted file: $name")

  def defaultSystem[A](fa: Library => A): A =
    fa(makeDefault)

trait BlobStore:
  def write(data: Array[Byte], path: String): Long

object BlboStore:
  def write(data: Array[Byte], path: String)(using s: BlobStore): Long =
    s.write(data, path)

  def makeDefault = new BlobStore:
    override def write(data: Array[Byte], path: String): Long =
      println(s"written data [${data.length}] in path: $path")
      data.length

object UploadsUseCase:
  // capabilities still needs to be provided, e.g. (using Library, BlobStore)
  def uploadSingleFile(data: Array[Byte])(using Library, BlobStore) =
    val name = UUID.randomUUID().toString
    Library.register(name)
    BlboStore.write(data, name)