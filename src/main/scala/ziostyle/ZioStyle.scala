package ziostyle

import zio.*

import java.util.UUID

object ZioStyle extends ZIOAppDefault:
  def run = (
    for
      _ <- Console.printLine("Old Scala style of capabilities")
      _ <- UploadsUseCase.uploadSingleFile("123qwe".getBytes)
    yield ())
    .provide(Library.defaultLayer, BlboStore.defaultLayer)

trait Library:
  def register(name: String): Task[Unit]

  def delete(name: String): Task[Unit]

object Library:
  def register(name: String): ZIO[Library, Throwable, Unit] =
    ZIO.serviceWithZIO[Library](_.register(name))

  def makeDefault = new Library:
    override def register(name: String): Task[Unit] =
      Console.printLine(s"registered file: $name")

    override def delete(name: String): Task[Unit] =
      Console.printLine(s"deleted file: $name")

  val defaultLayer = ZLayer.succeed(makeDefault)

trait BlobStore:
  def write(data: Array[Byte], path: String): Task[Long]

object BlboStore:
  def write(data: Array[Byte], path: String): ZIO[BlobStore, Throwable, Long] =
    ZIO.serviceWithZIO[BlobStore](_.write(data, path))

  def makeDefault = new BlobStore:
    override def write(data: Array[Byte], path: String): Task[Long] =
      Console.printLine(s"written data [${data.length}] in path: $path").as(data.length)

  val defaultLayer = ZLayer.succeed(makeDefault)

object UploadsUseCase:
  // capabilities still needs to be provided, e.g. (using Library, BlobStore)
  def uploadSingleFile(data: Array[Byte]) =
    val name = UUID.randomUUID().toString
    Library.register(name) *> BlboStore.write(data, name)
