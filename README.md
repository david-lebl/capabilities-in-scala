# Capabilities in Scala: Technical Deep Dive and Comparison

> ⚠️ **Note**  
> This README serves as a **temporary main document** and is primarily intended for **research and exploration** purposes.  
> The finalized content will be consolidated and refined into the **main article** at a later stage.

> ⚠️ **Status:** Work in Progress
> **Author:** David Lebl
> **Last Updated:** 2025-01-20

---

This document provides an in-depth technical comparison of three approaches to capability-based design in Scala:
1. **New Style** - Context Functions (Scala 3.x with capture checking)
2. **Old Style** - Implicit Parameters (Scala 2.x/3.x)
3. **ZIO Style** - ZIO Environment with ZLayer

---

## Table of Contents

1. [Introduction](#introduction)
2. [Core Concepts](#core-concepts)
3. [Pattern 1: New Style (Context Functions)](#pattern-1-new-style-context-functions)
4. [Pattern 2: Old Style (Implicits)](#pattern-2-old-style-implicits)
5. [Pattern 3: ZIO Style (ZIO Environment)](#pattern-3-zio-style-zio-environment)
6. [Complete Working Example](#complete-working-example)
7. [Service vs Use Case Pattern](#service-vs-use-case-pattern)
8. [Capability Composition Strategies](#capability-composition-strategies)
9. [Testing Strategies](#testing-strategies)
10. [Performance Considerations](#performance-considerations)
11. [Migration Paths](#migration-paths)
12. [Recommendations](#recommendations)

---

## Introduction

### What Are Capabilities?

**Capabilities** are abstractions that represent **what an operation can do**, not **how it does it**. They define:
- Input parameters
- Output types
- Side effects (in type signatures)
- But NOT the concrete implementation

### Why Capabilities Matter

In functional programming, we want to:
- ✅ **Separate concerns**: Business logic from infrastructure
- ✅ **Enable testability**: Mock infrastructure dependencies
- ✅ **Improve composition**: Combine operations declaratively
- ✅ **Make effects explicit**: Track side effects in types

### The Three Approaches

| Approach | Key Feature | Best For |
|----------|-------------|----------|
| **Context Functions** | `A ?=> B` syntax, implicit by default | Pure Scala 3, no external dependencies |
| **Implicit Parameters** | `implicit` keyword, manual passing | Scala 2/3 compatibility |
| **ZIO Environment** | `ZIO[R, E, A]` monad, ZLayer DI | Complex async/concurrent systems |

---

## Core Concepts

### Service vs Use Case

Before diving into implementations, understand these two patterns:

#### Service Pattern

**Definition**: A trait/interface that groups related operations sharing dependencies.

**Characteristics**:
- All methods share same dependencies
- Instantiated as objects (potentially stateful)
- Good for infrastructure boundaries (repositories, HTTP clients)

**Example**:
```scala
trait Library:
  def register(name: String): Unit
  def delete(name: String): Unit
  def find(name: String): Option[Book]
  def listAll(): List[Book]
```

**When to use**:
- ✅ Infrastructure/output ports (DB, HTTP, file systems)
- ✅ Operations are conceptually related
- ✅ Need multiple implementations (mock, production, test)

#### Use Case Pattern

**Definition**: Standalone functions representing business operations, each with minimal dependencies.

**Characteristics**:
- Each function declares only needed capabilities
- Stateless, pure orchestration logic
- Grouped by domain, not by shared dependencies

**Example**:
```scala
object BookUseCases:
  // Only needs Library
  def registerBook(name: String): Library ?=> Unit =
    Library.register(name)

  // Needs Library AND Notification
  def registerAndNotify(name: String): (Library & Notification) ?=> Unit =
    Library.register(name)
    Notification.send(s"Book $name registered")

  // Only needs Notification
  def sendReminder(email: String): Notification ?=> Unit =
    Notification.send(s"Reminder sent to $email")
```

**When to use**:
- ✅ Business logic/application layer
- ✅ Operations have different dependency sets
- ✅ Want to test operations in isolation
- ✅ Following CQRS/DDD principles

---

## Pattern 1: New Style (Context Functions)

### Overview

Scala 3 introduces **context functions** with the `?=>` syntax:

```scala
type ContextFunction[A, B] = A ?=> B
```

**Key features**:
- Context parameters are **implicit by default**
- Supports **capture checking** (upcoming Scala 3.x)
- Ergonomic accessor pattern
- No external dependencies

### Service Definition

```scala
// 1. Define the capability interface
trait Library:
  def register(name: String): Unit
  def delete(name: String): Unit
  def find(name: String): Option[Book]

// 2. Define accessors and implementations in companion object
object Library:
  // Accessor methods - provide ergonomic API
  def register(name: String): Library ?=> Unit =
    summon[Library].register(name)

  def delete(name: String): Library ?=> Unit =
    summon[Library].delete(name)

  def find(name: String): Library ?=> Option[Book] =
    summon[Library].find(name)

  // Mock implementation
  def makeMock: Library = new Library:
    private var books = Map.empty[String, Book]

    override def register(name: String): Unit =
      println(s"[Mock] Registered: $name")
      books = books + (name -> Book(name))

    override def delete(name: String): Unit =
      println(s"[Mock] Deleted: $name")
      books = books - name

    override def find(name: String): Option[Book] =
      books.get(name)

  // Production implementation
  def makePostgres(db: Database): Library = new Library:
    override def register(name: String): Unit =
      db.execute(s"INSERT INTO books (name) VALUES ('$name')")

    override def delete(name: String): Unit =
      db.execute(s"DELETE FROM books WHERE name = '$name'")

    override def find(name: String): Option[Book] =
      db.query(s"SELECT * FROM books WHERE name = '$name'").headOption

  // Provide context within a scope
  def withMock[A](f: Library ?=> A): A =
    f(using makeMock)

  def withPostgres[A](db: Database)(f: Library ?=> A): A =
    f(using makePostgres(db))
```

### Use Case Definition

```scala
object BookUseCases:
  // Use case with single capability
  def registerBook(name: String): Library ?=> Unit =
    Library.register(name)
    println(s"Book registered: $name")

  // Use case with multiple capabilities
  def registerAndNotify(name: String, email: String): (Library & Notification) ?=> Unit =
    Library.register(name)
    Notification.sendEmail(email, s"Book '$name' registered")

  // Complex use case
  def transferBook(name: String, fromLib: String, toLib: String): (Library & Audit) ?=> Unit =
    Library.find(name) match
      case Some(book) =>
        Library.delete(name)
        Library.register(name)
        Audit.log(s"Transferred $name from $fromLib to $toLib")
      case None =>
        throw new Exception(s"Book $name not found")
```

### Usage

```scala
@main def newStyleMain(): Unit =
  // Single capability
  Library.withMock {
    BookUseCases.registerBook("Scala in Depth")
  }

  // Multiple capabilities
  given Library = Library.makeMock
  given Notification = Notification.makeMock

  BookUseCases.registerAndNotify("Functional Programming", "user@example.com")
```

### Pros and Cons

**Pros**:
- ✅ Clean syntax, implicit by default
- ✅ No external dependencies
- ✅ Future-proof with capture checking
- ✅ Ergonomic accessor pattern

**Cons**:
- ❌ Scala 3 only
- ❌ No built-in dependency graph visualization
- ❌ Manual error handling (no typed errors)
- ❌ No async/concurrency primitives

---

## Pattern 2: Old Style (Implicits)

### Overview

Classic Scala 2.x approach using `implicit` keyword:

```scala
def operation(param: String)(implicit capability: Library): Unit
```

**Key features**:
- Works in Scala 2.x and 3.x
- Requires explicit `implicit` keyword in lambda
- Similar to context functions but more verbose

### Service Definition

```scala
// 1. Define the capability interface (same as before)
trait Library:
  def register(name: String): Unit
  def delete(name: String): Unit
  def find(name: String): Option[Book]

// 2. Companion object with accessors
object Library:
  // Accessor methods with explicit implicit
  def register(name: String)(implicit lib: Library): Unit =
    lib.register(name)

  def delete(name: String)(implicit lib: Library): Unit =
    lib.delete(name)

  def find(name: String)(implicit lib: Library): Option[Book] =
    lib.find(name)

  // Implementations (same as new style)
  def makeMock: Library = new Library:
    private var books = Map.empty[String, Book]
    override def register(name: String): Unit = ???
    override def delete(name: String): Unit = ???
    override def find(name: String): Option[Book] = ???

  // Provide context
  def withMock[A](f: Library => A): A =
    f(makeMock)
```

### Use Case Definition

```scala
object BookUseCases:
  // Use case with single capability
  def registerBook(name: String)(implicit lib: Library): Unit =
    Library.register(name)
    println(s"Book registered: $name")

  // Use case with multiple capabilities
  def registerAndNotify(name: String, email: String)(implicit lib: Library, notif: Notification): Unit =
    Library.register(name)
    Notification.sendEmail(email, s"Book '$name' registered")
```

### Usage

```scala
@main def oldStyleMain(): Unit =
  // Must explicitly mark lambda as implicit
  Library.withMock { implicit lib =>
    BookUseCases.registerBook("Scala in Depth")
  }

  // Or declare implicit in scope
  implicit val lib: Library = Library.makeMock
  implicit val notif: Notification = Notification.makeMock

  BookUseCases.registerAndNotify("Functional Programming", "user@example.com")
```

### Pros and Cons

**Pros**:
- ✅ Works in Scala 2.x and 3.x
- ✅ Mature, well-understood pattern
- ✅ No external dependencies

**Cons**:
- ❌ More verbose than context functions
- ❌ Must explicitly mark lambda parameters as `implicit`
- ❌ No capture checking
- ❌ Manual error handling
- ❌ No async/concurrency primitives

---

## Pattern 3: ZIO Style (ZIO Environment)

### Overview

ZIO uses the `ZIO[R, E, A]` monad where:
- `R` = **Environment/Requirements** (capabilities)
- `E` = **Error type** (typed errors)
- `A` = **Success type** (result)

**Key features**:
- Built-in async/concurrency
- Typed error handling
- ZLayer for dependency injection
- Dependency graph visualization
- Reloadable services

### Service Definition

```scala
import zio.*

// 1. Define the capability interface with ZIO effects
trait Library:
  def register(name: String): IO[LibraryError, Unit]
  def delete(name: String): IO[LibraryError, Unit]
  def find(name: String): IO[LibraryError, Option[Book]]

// 2. Define domain errors
sealed trait LibraryError extends NoStackTrace
object LibraryError:
  case class BookNotFound(name: String) extends LibraryError
  case class DatabaseError(cause: Throwable) extends LibraryError

// 3. Companion object with accessors and layers
object Library:
  // Accessor methods (optional but ergonomic)
  // can also be generate using `@accessible` annotation attached the trait
  def register(name: String): ZIO[Library, LibraryError, Unit] =
    ZIO.serviceWithZIO[Library](_.register(name))

  def delete(name: String): ZIO[Library, LibraryError, Unit] =
    ZIO.serviceWithZIO[Library](_.delete(name))

  def find(name: String): ZIO[Library, LibraryError, Option[Book]] =
    ZIO.serviceWithZIO[Library](_.find(name))

  // Mock implementation
  final case class Mock(ref: Ref[Map[String, Book]]) extends Library:
    override def register(name: String): IO[LibraryError, Unit] =
      for
        _ <- Console.printLine(s"[Mock] Registered: $name").orDie
        _ <- ref.update(_ + (name -> Book(name)))
      yield ()

    override def delete(name: String): IO[LibraryError, Unit] =
      for
        _ <- Console.printLine(s"[Mock] Deleted: $name").orDie
        _ <- ref.update(_ - name)
      yield ()

    override def find(name: String): IO[LibraryError, Option[Book]] =
      ref.get.map(_.get(name))

  // Mock layer
  val mock: ZLayer[Any, Nothing, Library] =
    ZLayer.fromZIO:
      Ref.make(Map.empty[String, Book]).map(Mock(_))

  // Production implementation
  final case class Postgres(db: Database) extends Library:
    override def register(name: String): IO[LibraryError, Unit] =
      ZIO.attempt:
        db.execute(s"INSERT INTO books (name) VALUES ('$name')")
      .mapError(LibraryError.DatabaseError(_))

    override def delete(name: String): IO[LibraryError, Unit] =
      ZIO.attempt:
        db.execute(s"DELETE FROM books WHERE name = '$name'")
      .mapError(LibraryError.DatabaseError(_))

    override def find(name: String): IO[LibraryError, Option[Book]] =
      ZIO.attempt:
        db.query(s"SELECT * FROM books WHERE name = '$name'").headOption
      .mapError(LibraryError.DatabaseError(_))

  // Production layer (requires Database)
  val postgres: ZLayer[Database, Nothing, Library] =
    ZLayer.fromFunction(Postgres(_))
```

### Use Case Definition

```scala
object BookUseCases:
  // Use case with single capability
  def registerBook(name: String): ZIO[Library, LibraryError, Unit] =
    for
      _ <- Library.register(name)
      _ <- Console.printLine(s"Book registered: $name").orDie
    yield ()

  // Use case with multiple capabilities
  def registerAndNotify(name: String, email: String): ZIO[Library & Notification, LibraryError | NotificationError, Unit] =
    for
      _ <- Library.register(name)
      _ <- Notification.sendEmail(email, s"Book '$name' registered")
    yield ()

  // Complex use case with error handling
  def transferBook(name: String, fromLib: String, toLib: String): ZIO[Library & Audit, LibraryError | AuditError, Unit] =
    for
      bookOpt <- Library.find(name)
      book <- ZIO.fromOption(bookOpt).orElseFail(LibraryError.BookNotFound(name))
      _ <- Library.delete(name)
      _ <- Library.register(name)
      _ <- Audit.log(s"Transferred $name from $fromLib to $toLib")
    yield ()
```

### Usage

```scala
object ZioStyleApp extends ZIOAppDefault:
  def run =
    // Single capability
    val program1 = BookUseCases.registerBook("Scala in Depth")

    program1.provide(Library.mock)

    // Multiple capabilities with error union
    val program2 = BookUseCases.registerAndNotify("Functional Programming", "user@example.com")

    program2.provide(
      Library.mock,
      Notification.mock
    )

    // With production layers
    val program3 = BookUseCases.transferBook("Clean Code", "LibA", "LibB")

    program3.provide(
      Database.live,           // Provides Database
      Library.postgres,        // Requires Database, provides Library
      Audit.postgres           // Requires Database, provides Audit
    )
```

### Advanced: ZLayer Composition

```scala
// Automatic dependency resolution
val appLayer: ZLayer[Any, Nothing, Library & Notification & Audit] =
  ZLayer.make[Library & Notification & Audit](
    // Infrastructure layers
    Database.live,

    // Service layers (ZIO resolves dependencies automatically)
    Library.postgres,
    Notification.smtp,
    Audit.postgres
  )

// Debug dependency graph
val debugLayer = appLayer.tap(layer => Console.printLine(layer.toString).orDie)

// Use in application
val program = BookUseCases.transferBook("Clean Code", "LibA", "LibB")
program.provide(appLayer)
```

### Pros and Cons

**Pros**:
- ✅ Typed errors (union types for error composition)
- ✅ Built-in async/concurrency primitives
- ✅ Automatic dependency resolution
- ✅ Dependency graph visualization
- ✅ Reloadable services (hot reload in production)
- ✅ Resource management (automatic cleanup)
- ✅ Testing utilities (TestClock, TestRandom, etc.)

**Cons**:
- ❌ Requires ZIO library dependency
- ❌ Learning curve (monad, ZIO operators)
- ❌ More boilerplate for simple cases
- ❌ Heavier runtime

---

## Complete Working Example

Let's implement a complete **Library Management System** in all three styles.

### Domain Model (Shared)

```scala
case class Book(name: String, author: String, isbn: String)
case class User(id: String, name: String, email: String)

// Simplified database interface
trait Database:
  def execute(sql: String): Unit
  def query(sql: String): List[Map[String, String]]
```

### Capabilities (Shared Interfaces)

```scala
// Library operations
trait Library:
  def register(book: Book): Unit  // or IO[LibraryError, Unit] for ZIO
  def delete(isbn: String): Unit
  def find(isbn: String): Option[Book]
  def listAll(): List[Book]

// Notification operations
trait Notification:
  def sendEmail(to: String, subject: String, body: String): Unit  // or IO[NotificationError, Unit]

// Audit logging
trait Audit:
  def log(event: String): Unit  // or IO[AuditError, Unit]
```

### Implementation: New Style

```scala
// Services
object Library:
  def register(book: Book): Library ?=> Unit =
    summon[Library].register(book)

  def find(isbn: String): Library ?=> Option[Book] =
    summon[Library].find(isbn)

  def makeMock: Library = new Library:
    private var books = Map.empty[String, Book]
    override def register(book: Book): Unit =
      books = books + (book.isbn -> book)
      println(s"[Mock Library] Registered: ${book.name}")

    override def delete(isbn: String): Unit =
      books = books - isbn

    override def find(isbn: String): Option[Book] =
      books.get(isbn)

    override def listAll(): List[Book] =
      books.values.toList

  def withMock[A](f: Library ?=> A): A =
    f(using makeMock)

// Use Cases
object LibraryUseCases:
  def addBook(book: Book): (Library & Notification & Audit) ?=> Unit =
    Library.register(book)
    Notification.sendEmail(
      "admin@library.com",
      "New Book Added",
      s"Book '${book.name}' by ${book.author} added to library"
    )
    Audit.log(s"Book added: ${book.isbn}")

  def searchAndNotify(isbn: String, userEmail: String): (Library & Notification) ?=> Unit =
    Library.find(isbn) match
      case Some(book) =>
        Notification.sendEmail(
          userEmail,
          "Book Found",
          s"Found: ${book.name} by ${book.author}"
        )
      case None =>
        Notification.sendEmail(
          userEmail,
          "Book Not Found",
          s"Sorry, book with ISBN $isbn not found"
        )

// Main Application
@main def newStyleLibraryApp(): Unit =
  given Library = Library.makeMock
  given Notification = Notification.makeMock
  given Audit = Audit.makeMock

  val book = Book("Scala in Depth", "Joshua Suereth", "978-1935182706")

  LibraryUseCases.addBook(book)
  LibraryUseCases.searchAndNotify("978-1935182706", "user@example.com")
```

### Implementation: ZIO Style

```scala
import zio.*

// Error types
sealed trait LibraryError extends NoStackTrace
object LibraryError:
  case class BookNotFound(isbn: String) extends LibraryError:
    val message = s"Book with ISBN $isbn not found"
  case class BookAlreadyExists(isbn: String) extends LibraryError:
    val message = s"Book with ISBN $isbn already exists"
  case class DatabaseError(cause: Throwable) extends LibraryError:
    val message = s"Database error: ${cause.getMessage}"
    override def getCause: Throwable = cause

sealed trait NotificationError extends NoStackTrace
sealed trait AuditError extends NoStackTrace

// Services with ZIO
trait Library:
  def register(book: Book): IO[LibraryError, Unit]
  def delete(isbn: String): IO[LibraryError, Unit]
  def find(isbn: String): IO[LibraryError, Option[Book]]
  def listAll(): IO[LibraryError, List[Book]]

object Library:
  // Accessors
  def register(book: Book): ZIO[Library, LibraryError, Unit] =
    ZIO.serviceWithZIO[Library](_.register(book))

  def find(isbn: String): ZIO[Library, LibraryError, Option[Book]] =
    ZIO.serviceWithZIO[Library](_.find(isbn))

  // Mock implementation
  final case class Mock(ref: Ref[Map[String, Book]]) extends Library:
    override def register(book: Book): IO[LibraryError, Unit] =
      for
        books <- ref.get
        _ <- ZIO.when(books.contains(book.isbn)):
               ZIO.fail(LibraryError.BookAlreadyExists(book.isbn))
        _ <- ref.update(_ + (book.isbn -> book))
        _ <- Console.printLine(s"[Mock Library] Registered: ${book.name}").orDie
      yield ()

    override def delete(isbn: String): IO[LibraryError, Unit] =
      ref.update(_ - isbn)

    override def find(isbn: String): IO[LibraryError, Option[Book]] =
      ref.get.map(_.get(isbn))

    override def listAll(): IO[LibraryError, List[Book]] =
      ref.get.map(_.values.toList)

  val mock: ZLayer[Any, Nothing, Library] =
    ZLayer.fromZIO:
      Ref.make(Map.empty[String, Book]).map(Mock(_))

// Use Cases
object LibraryUseCases:
  def addBook(book: Book): ZIO[Library & Notification & Audit, LibraryError | NotificationError | AuditError, Unit] =
    for
      _ <- Library.register(book)
      _ <- Notification.sendEmail(
        "admin@library.com",
        "New Book Added",
        s"Book '${book.name}' by ${book.author} added to library"
      )
      _ <- Audit.log(s"Book added: ${book.isbn}")
    yield ()

  def searchAndNotify(isbn: String, userEmail: String): ZIO[Library & Notification, LibraryError | NotificationError, Unit] =
    for
      bookOpt <- Library.find(isbn)
      message = bookOpt match
        case Some(book) => s"Found: ${book.name} by ${book.author}"
        case None => s"Sorry, book with ISBN $isbn not found"
      _ <- Notification.sendEmail(userEmail, "Search Result", message)
    yield ()

// Main Application
object ZioLibraryApp extends ZIOAppDefault:
  def run =
    val book = Book("Scala in Depth", "Joshua Suereth", "978-1935182706")

    val program = for
      _ <- LibraryUseCases.addBook(book)
      _ <- LibraryUseCases.searchAndNotify("978-1935182706", "user@example.com")
    yield ()

    program.provide(
      Library.mock,
      Notification.mock,
      Audit.mock
    )
```

---

## Service vs Use Case Pattern

### When to Use Each

| Pattern | Use For | Example |
|---------|---------|---------|
| **Service** | Infrastructure boundaries, shared dependencies | `DatabaseRepository`, `HttpClient`, `FileStorage` |
| **Use Case** | Business logic, orchestration, different dependency sets | `RegisterUser`, `ProcessPayment`, `SendInvoice` |

### Problem: Service Pattern for Business Logic

```scala
// ❌ BAD: Service pattern for unrelated business operations
trait UserService:
  def register(user: User): Unit
  def updateProfile(user: User): Unit
  def sendWelcomeEmail(user: User): Unit
  def deleteAccount(userId: String): Unit
  def exportUserData(userId: String): Unit
  def calculateLoyaltyPoints(userId: String): Int
  // ... 50 more methods

// All methods require ALL dependencies, even if not used
object UserService:
  def make(db: Database, email: EmailService, storage: Storage, analytics: Analytics): UserService = ???

// Testing requires mocking everything
class UserServiceTest:
  test("register should save user"):
    val mockDb = mock[Database]
    val mockEmail = mock[EmailService]
    val mockStorage = mock[Storage]       // Not needed for this test!
    val mockAnalytics = mock[Analytics]  // Not needed for this test!

    val service = UserService.make(mockDb, mockEmail, mockStorage, mockAnalytics)
    service.register(user)
```

### Solution: Use Case Pattern

```scala
// ✅ GOOD: Use case pattern with minimal dependencies
object UserUseCases:
  // Only needs Database
  def register(user: User): Database ?=> Unit =
    Database.save(user)

  // Needs Database AND Email
  def registerWithWelcome(user: User): (Database & EmailService) ?=> Unit =
    Database.save(user)
    EmailService.send(user.email, "Welcome!")

  // Only needs Analytics
  def calculateLoyaltyPoints(userId: String): Analytics ?=> Int =
    Analytics.getPoints(userId)

// Testing is simple and focused
class UserUseCasesTest:
  test("register should save user"):
    given Database = mockDatabase  // Only mock what's needed!

    UserUseCases.register(user)
    verify(mockDatabase).save(user)
```

---

## Capability Composition Strategies

### Problem: Deep Capability Nesting

```scala
// ❌ BAD: Too many capabilities propagated everywhere
def complexOperation(): (Db & Cache & Email & SMS & Analytics & Logging & Metrics & Auth & Config & S3) ?=> Unit =
  ???

def anotherOperation(): (Db & Cache & Email & SMS & Analytics & Logging & Metrics & Auth & Config & S3) ?=> Unit =
  ???
```

### Solution 1: System Wrapper

```scala
// ✅ GOOD: Group related capabilities
case class AppSystem(
  db: Database,
  cache: Cache,
  email: EmailService,
  sms: SmsService,
  analytics: Analytics,
  logging: Logging,
  metrics: Metrics,
  auth: Auth,
  config: Config,
  s3: S3
)

// Now operations only need one capability
def complexOperation(): AppSystem ?=> Unit =
  val system = summon[AppSystem]
  system.db.query(...)
  system.cache.get(...)
```

### Solution 2: Layered Capabilities

```scala
// Infrastructure layer
case class InfraSystem(db: Database, cache: Cache, s3: S3)

// Communication layer
case class CommSystem(email: EmailService, sms: SmsService)

// Observability layer
case class ObsSystem(logging: Logging, metrics: Metrics, analytics: Analytics)

// Operations declare only needed layers
def dataOperation(): InfraSystem ?=> Unit = ???
def notificationOperation(): CommSystem ?=> Unit = ???
def monitoredOperation(): (InfraSystem & ObsSystem) ?=> Unit = ???
```

### Solution 3: Use Case Composition (ZIO)

```scala
// Build complex operations from simple use cases
object OrderUseCases:
  def validateOrder(order: Order): ZIO[OrderValidator, ValidationError, Order] = ???
  def chargePayment(order: Order): ZIO[PaymentService, PaymentError, Receipt] = ???
  def sendConfirmation(order: Order): ZIO[EmailService, EmailError, Unit] = ???

  // Compose into complex workflow
  def processOrder(order: Order): ZIO[OrderValidator & PaymentService & EmailService, OrderError, Receipt] =
    for
      validated <- validateOrder(order)
      receipt <- chargePayment(validated)
      _ <- sendConfirmation(validated)
    yield receipt
```

---

## Testing Strategies

### New Style Testing

```scala
class LibraryUseCasesTest extends munit.FunSuite:
  test("addBook should register and notify"):
    var registered: Option[Book] = None
    var emailSent: Option[(String, String, String)] = None
    var auditLog: Option[String] = None

    // Create test implementations
    given Library = new Library:
      override def register(book: Book): Unit =
        registered = Some(book)
      override def find(isbn: String): Option[Book] = registered.filter(_.isbn == isbn)
      override def delete(isbn: String): Unit = ()
      override def listAll(): List[Book] = registered.toList

    given Notification = new Notification:
      override def sendEmail(to: String, subject: String, body: String): Unit =
        emailSent = Some((to, subject, body))

    given Audit = new Audit:
      override def log(event: String): Unit =
        auditLog = Some(event)

    // Execute use case
    val book = Book("Test Book", "Author", "123")
    LibraryUseCases.addBook(book)

    // Assertions
    assertEquals(registered, Some(book))
    assert(emailSent.isDefined)
    assert(auditLog.isDefined)
```

### ZIO Style Testing

```scala
import zio.test.*
import zio.test.Assertion.*

object LibraryUseCasesSpec extends ZIOSpecDefault:
  def spec = suite("LibraryUseCases")(
    test("addBook should register and notify"):
      for
        book <- ZIO.succeed(Book("Test Book", "Author", "123"))
        _ <- LibraryUseCases.addBook(book)

        // Verify using test layers
        registered <- TestLibrary.getRegisteredBooks
        emails <- TestNotification.getSentEmails
        logs <- TestAudit.getLogs
      yield
        assertTrue(
          registered.contains(book),
          emails.nonEmpty,
          logs.nonEmpty
        )
    .provide(
      TestLibrary.layer,
      TestNotification.layer,
      TestAudit.layer
    )
  )

// Test implementations
object TestLibrary:
  def layer: ZLayer[Any, Nothing, Library] =
    ZLayer.fromZIO:
      Ref.make(List.empty[Book]).map: ref =>
        new Library:
          def register(book: Book): IO[LibraryError, Unit] =
            ref.update(_ :+ book)
          // ... other methods

  def getRegisteredBooks: ZIO[Library, Nothing, List[Book]] =
    ZIO.serviceWith[Library]:
      case impl: TestLibrary.Impl => impl.getBooks
```

---

## Performance Considerations

### Context Functions vs Implicits

**Runtime Performance**: Nearly identical - both compile to implicit parameter passing.

**Compile Time**: Context functions may be slightly faster (less implicit resolution complexity).

### ZIO vs Direct Style

**Runtime Overhead**:
- ZIO has allocation overhead for effect construction
- Optimized for async/concurrent scenarios
- For pure CPU-bound sync code, direct style is faster

**When ZIO Wins**:
- Concurrent operations (parallel, race, timeout)
- Async I/O (HTTP, database, file systems)
- Complex error handling
- Resource management (auto-cleanup)

**Benchmark Example**:
```scala
// Direct style (fastest for sync code)
def syncOperation(): Unit =
  // Pure CPU-bound logic
  (1 to 1000000).sum

// ZIO style (better for async/concurrent)
def zioOperation(): UIO[Unit] =
  ZIO.foreach((1 to 1000))(i => ZIO.succeed(i * 2)).unit
```

---

## Migration Paths

### From Implicits to Context Functions

```scala
// Before (Scala 2/3)
def operation(param: String)(implicit db: Database): Unit = ???

// After (Scala 3)
def operation(param: String): Database ?=> Unit = ???
```

**Steps**:
1. Update Scala version to 3.x
2. Replace `(implicit x: T)` with `T ?=>`
3. Remove `implicit` from lambda parameters (now implicit by default)
4. Test thoroughly

### From Direct Style to ZIO

```scala
// Before
trait Library:
  def register(book: Book): Unit

// After
trait Library:
  def register(book: Book): IO[LibraryError, Unit]
```

**Steps**:
1. Add ZIO dependency
2. Wrap return types in `IO[E, A]` or `Task[A]`
3. Replace implementations with ZIO effects
4. Create ZLayer instances
5. Update call sites to use `.flatMap` / for-comprehensions
6. Provide layers at application entry point

---

## Recommendations

### Choose Context Functions When:
- ✅ Pure Scala 3 project
- ✅ No heavy async/concurrency needs
- ✅ Want minimal dependencies
- ✅ Prefer functional style without monads

### Choose Implicits When:
- ✅ Need Scala 2.x compatibility
- ✅ Same benefits as context functions (but more verbose)

### Choose ZIO When:
- ✅ Complex async/concurrent operations
- ✅ Need typed error handling
- ✅ Want dependency graph visualization
- ✅ Need advanced features (metrics, tracing, testing utilities)
- ✅ Building production systems with high reliability requirements

### Hybrid Approach:
- Use **Context Functions/Implicits** for pure domain logic
- Use **ZIO** for infrastructure boundaries (DB, HTTP, etc.)
- Best of both worlds!

---

## Conclusion

All three approaches enable capability-based design, but serve different needs:

| Approach | Best For | Trade-off |
|----------|----------|-----------|
| **Context Functions** | Modern Scala 3 apps, simple use cases | No async primitives |
| **Implicits** | Scala 2/3 compatibility | More verbose |
| **ZIO** | Production systems with complex async needs | Learning curve, dependency |

**My Recommendation**:
- Start with **Context Functions** (or Implicits for Scala 2)
- Add **ZIO** when you need async, error handling, or advanced features
- Always use **Use Case Pattern** for business logic
- Always use **Service Pattern** for infrastructure

---

## References

- [Scala 3 Context Functions](https://docs.scala-lang.org/scala3/reference/contextual/context-functions.html)
- [ZIO Documentation](https://zio.dev)
- [Nicolas Rinaudo - Capabilities in Scala](https://nrinaudo.github.io/articles/context-functions.html)
- [Martin Odersky - Direct Style Scala](https://www.youtube.com/watch?v=0Fm0y4K4YO8)

---

**Version**: 0.1
**Last Updated**: 2025-01-20
**License**: MIT