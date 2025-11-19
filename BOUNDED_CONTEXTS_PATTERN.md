# Bounded Contexts Pattern - Quick Summary

**Microservices Architecture with DDD and Capability Bundles**

---

## Overview

The **Bounded Contexts Pattern** demonstrates how to structure microservices using Domain-Driven Design (DDD) principles with capability-based design in Scala 3, where each bounded context bundles its capabilities together.

### Core Principle

**Each bounded context bundles all its capabilities into a single context capabilities case class, providing a cohesive unit of functionality.**

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                 Shared Infrastructure                    │
│  (EventBus, MessageQueue, Logger)                       │
└─────────────────────────────────────────────────────────┘
         │                    │                    │
         ▼                    ▼                    ▼
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│  Order Context   │  │ Payment Context  │  │ Shipping Context │
├──────────────────┤  ├──────────────────┤  ├──────────────────┤
│ Capabilities:    │  │ Capabilities:    │  │ Capabilities:    │
│ - OrderRepo      │  │ - PaymentGateway │  │ - ShippingService│
│ - OrderValidator │  │ - PaymentRepo    │  │ - ShipmentRepo   │
│ - EventBus       │  │ - EventBus       │  │ - EventBus       │
│ - Logger         │  │ - Logger         │  │ - Logger         │
│                  │  │                  │  │                  │
│ Use Cases:       │  │ Use Cases:       │  │ Use Cases:       │
│ - createOrder    │  │ - processPayment │  │ - createShipment │
│ - confirmOrder   │  │                  │  │                  │
│ - cancelOrder    │  │                  │  │                  │
└──────────────────┘  └──────────────────┘  └──────────────────┘
         │                    │                    │
         └────────────────────┴────────────────────┘
                              │
                              ▼
                  ┌───────────────────────┐
                  │ Order Fulfillment Saga│
                  │  (Cross-Context)      │
                  └───────────────────────┘
```

---

## Key Components

### 1. Bounded Context Definition

Each context contains:
- Domain models
- Fine-grained capabilities (repositories, validators, gateways)
- Capability bundle case class
- Use cases that require the full bundle
- Mock implementations

```scala
object OrderContext:
  // Domain Models
  case class Order(id: String, customerId: String, items: List[String], ...)

  // Fine-grained Capabilities
  trait OrderRepository:
    def save(order: Order): Unit
    def findById(id: String): Option[Order]

  trait OrderValidator:
    def validateOrder(order: Order): Either[String, Order]
    def checkInventory(items: List[String]): Boolean

  // Capability Bundle - aggregates all context capabilities
  case class OrderContextCapabilities(
    repository: OrderRepository,
    validator: OrderValidator,
    eventBus: EventBus,
    logger: Logger
  )

  // Use Cases - require the full bundle
  object OrderUseCases:
    def createOrder(customerId: String, items: List[String], amount: Double):
      OrderContextCapabilities ?=> Either[String, Order] =

      val ctx = summon[OrderContextCapabilities]
      given OrderRepository = ctx.repository
      given OrderValidator = ctx.validator
      given EventBus = ctx.eventBus
      given Logger = ctx.logger

      // Business logic here...
      Logger.log("OrderContext", s"Creating order for $customerId")
      // ...
```

### 2. Capability Bundle Pattern

**Structure:**
```scala
case class {Context}ContextCapabilities(
  capability1: Capability1,
  capability2: Capability2,
  eventBus: EventBus,
  logger: Logger
)
```

**Characteristics:**
- Bundles ALL context-specific capabilities
- Includes cross-cutting concerns (EventBus, Logger)
- Passed to use cases via context functions
- Single unit for dependency injection

### 3. Use Cases with Capability Bundles

**Pattern:**
```scala
object OrderUseCases:
  def createOrder(...): OrderContextCapabilities ?=> Either[String, Order] =
    val ctx = summon[OrderContextCapabilities]

    // Provide individual capabilities as given
    given OrderRepository = ctx.repository
    given OrderValidator = ctx.validator
    given EventBus = ctx.eventBus
    given Logger = ctx.logger

    // Business logic using accessor objects
    Logger.log("OrderContext", "Creating order")
    OrderValidator.validateOrder(order)
    OrderRepository.save(order)
    // ...
```

**Characteristics:**
- Use case signature requires full capability bundle
- Internally extracts and provides individual capabilities
- Uses accessor objects for clean API
- Contains pure business logic

### 4. Cross-Context Saga Pattern

**Pattern:**
```scala
object OrderFulfillmentSaga:
  def fulfillOrder(...): (
    OrderContext.OrderContextCapabilities,
    PaymentContext.PaymentContextCapabilities,
    ShippingContext.ShippingContextCapabilities
  ) ?=> FulfillmentResult =

    given OrderContext.OrderContextCapabilities = summon[...]
    given PaymentContext.PaymentContextCapabilities = summon[...]
    given ShippingContext.ShippingContextCapabilities = summon[...]

    val result = for
      // Step 1: Create Order
      order <- OrderContext.OrderUseCases.createOrder(...)

      // Step 2: Process Payment with compensation
      payment <- PaymentContext.PaymentUseCases.processPayment(...)
        .left.map { error =>
          OrderContext.OrderUseCases.cancelOrder(order.id, "Payment failed")
          error
        }

      // Step 3: Create Shipment with compensation
      shipment <- ShippingContext.ShippingUseCases.createShipment(...)
        .left.map { error =>
          OrderContext.OrderUseCases.cancelOrder(order.id, "Shipment failed")
          error
        }
    yield (order, payment, shipment)

    // Map to result...
```

**Characteristics:**
- Coordinates multiple bounded contexts
- Each context bundle passed via context functions
- Implements compensating transactions
- Uses for-comprehension with Either
- Handles distributed transaction logic

---

## Event-Driven Communication

### EventBus for Loose Coupling

```scala
trait EventBus:
  def publish(topic: String, event: String): Unit
  def subscribe(topic: String, handler: String => Unit): Unit

// Usage in context
EventBus.publish("orders", s"OrderCreated:${order.id}")
```

**Benefits:**
- Contexts communicate without direct dependencies
- Supports eventual consistency
- Easy to add event listeners
- Foundation for async processing

---

## Mock Implementations

### Building Context Capabilities

```scala
object BoundedContextMocks:
  // Individual mocks
  val mockOrderRepository: OrderContext.OrderRepository = ...
  val mockOrderValidator: OrderContext.OrderValidator = ...
  val mockEventBus: EventBus = ...
  val mockLogger: Logger = ...

  // Pre-built context capabilities
  given OrderContext.OrderContextCapabilities =
    OrderContext.OrderContextCapabilities(
      mockOrderRepository,
      mockOrderValidator,
      mockEventBus,
      mockLogger
    )

  given PaymentContext.PaymentContextCapabilities = ...
  given ShippingContext.ShippingContextCapabilities = ...
```

**Benefits:**
- Easy to swap entire context implementation
- Centralized mock management
- Simple to provide via given

---

## Usage Example

```scala
@main def boundedContextsMain(): Unit =
  import BoundedContextMocks.given

  // Single context operation
  val order = OrderContext.OrderUseCases.createOrder(
    "customer-123",
    List("item-1", "item-2"),
    299.99
  )

  // Cross-context saga
  val result = OrderFulfillmentSaga.fulfillOrder(
    customerId = "customer-789",
    items = List("laptop", "mouse"),
    amount = 1299.99,
    paymentMethod = "credit-card",
    shippingAddress = "123 Main St"
  )
```

---

## Pros and Cons

### ✅ Advantages

1. **Clear Context Boundaries**
   - Each context is self-contained
   - Easy to identify what belongs where
   - Natural microservice boundaries

2. **Simple Dependency Management**
   - One bundle per context
   - Easy to provide entire context at once
   - Clear what each context needs

3. **DDD Alignment**
   - Direct mapping to DDD bounded contexts
   - Ubiquitous language per context
   - Context isolation enforced

4. **Event-Driven Ready**
   - EventBus built-in
   - Loose coupling between contexts
   - Foundation for async communication

### ❌ Disadvantages

1. **Coarse-Grained Dependencies**
   - Use cases get ALL capabilities even if only need some
   - No fine-grained dependency declaration
   - Testing requires mocking entire bundle

2. **No Public API Layer**
   - Capability bundle is internal implementation detail
   - External consumers must know about bundles
   - No clean facade for service interface

3. **Less Flexibility**
   - Can't easily cherry-pick capabilities
   - Harder to compose use cases with different needs
   - More coupling than necessary

---

## When to Use Bounded Contexts Pattern

### ✅ Use When:
- Building event-driven microservices
- Clear DDD bounded context boundaries
- Contexts are truly independent
- Team organization mirrors contexts
- Simpler pattern preferred over service facades
- Early-stage development/prototyping

### ❌ Consider Service Facade Pattern Instead When:
- Need fine-grained dependency control
- Want clean public APIs for services
- Building complex orchestration
- Multiple use cases with different capability needs
- Production system requiring maximum testability
- Need to expose services to external consumers

---

## Comparison: Bounded Contexts vs Service Facade

| Aspect | Bounded Contexts | Service Facade |
|--------|------------------|----------------|
| **Dependency Granularity** | Coarse (full bundle) | Fine (only needed) |
| **Use Case Signature** | `ContextCapabilities ?=>` | `(Cap1, Cap2, ...) ?=>` |
| **Public API** | No dedicated layer | Service facade |
| **Testing** | Mock entire bundle | Mock only needed caps |
| **Complexity** | Simpler | More structured |
| **Best For** | Event-driven µservices | Production APIs |

---

## Key Patterns

### 1. Context Isolation
Each bounded context is completely independent with its own models and capabilities.

### 2. Capability Bundle
All context capabilities grouped into a single case class for easy dependency injection.

### 3. Saga Orchestration
Cross-context workflows coordinated via saga pattern with compensating transactions.

### 4. Event-Driven Communication
Contexts communicate via events for loose coupling and eventual consistency.

### 5. For-Comprehension Flow
Use Either and for-comprehension for clean error handling and sequencing.

---

## Migration to Service Facade

If you start with Bounded Contexts and need more flexibility:

**Step 1:** Keep use cases, extract capability bundles
```scala
// Change from
def createOrder(...): OrderContextCapabilities ?=> Result

// To
def createOrder(...): (OrderRepository, OrderValidator, EventBus, Logger) ?=> Result
```

**Step 2:** Add service facades
```scala
class OrderServiceFacade(capabilities: OrderContextCapabilities):
  given OrderRepository = capabilities.repository
  // ... provide all capabilities

  def createOrder(...) = OrderUseCases.createOrder(...)
```

**Step 3:** Update sagas to use facades
See `SERVICE_FACADE_PATTERN.md` for complete guide.

---

## Complete Example

See `src/main/scala/scaling/BoundedContexts.scala` for a complete working example with:
- 3 bounded contexts (Order, Payment, Shipping)
- Capability bundles for each context
- Use cases with business logic
- Cross-context saga with compensation
- Event-driven communication
- Mock implementations

Run with: `sbt "runMain scaling.boundedContextsMain"`

---

## Key Takeaways

1. **One bundle per context** - Group all context capabilities together
2. **Use cases require full bundle** - Simple dependency management
3. **Saga coordinates contexts** - Compensating transactions for failures
4. **Events for communication** - Loose coupling between contexts
5. **DDD alignment** - Natural mapping to bounded contexts
6. **Trade-off** - Simplicity vs fine-grained control
7. **Evolution path** - Can migrate to Service Facade when needed

---

**Author:** David Lebl
**Date:** 2025-11-19
**Version:** 1.0
**License:** MIT