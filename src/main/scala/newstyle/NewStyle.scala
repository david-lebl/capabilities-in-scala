package newstyle

import java.util.UUID

@main def newStyleMain = 
  println("New Scala style of capabilities")
  Library.defaultSystem { // scoped DI
    BlboStore.defaultSystem {
      UploadsUseCase.uploadSingleFile("123qwe".getBytes)
    }
  }

trait Library:
  def register(name: String): Unit

  def delete(name: String): Unit

object Library:
  def register(name: String): Library ?=> Unit =
    l ?=> l.register(name)

  def registerV2(name: String): Library ?=> Unit =
    summon[Library].register(name)

  def makeDefault = new Library:
    override def register(name: String): Unit = 
      println(s"registered file: $name")

    override def delete(name: String): Unit = 
      println(s"deleted file: $name")

  def defaultSystem[A](fa: Library ?=> A): A =
    fa(using makeDefault)

trait BlobStore:
  def write(data: Array[Byte], path: String): Long

object BlboStore:
  def write(data: Array[Byte], path: String)(using s: BlobStore): Long =
    s.write(data, path)

  def makeDefault = new BlobStore:
    override def write(data: Array[Byte], path: String): Long =
      println(s"written data [${data.length}] in path: $path")
      data.length

  def defaultSystem[A](fa: BlobStore ?=> A): A =
    fa(using makeDefault)   
  

object UploadsUseCase:
  // types and capabilities needs to be provided, e.g. (Library, BlobStore) ?=> Long
  def uploadSingleFile(data: Array[Byte]): (Library, BlobStore) ?=> Long =
    //    (library, blobStore) ?=>     // no need to write this, uff...
    val name = UUID.randomUUID().toString
    Library.register(name)
    BlboStore.write(data, name)