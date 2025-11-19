# Capability Scaling Experiments - Summary

This directory contains two comprehensive experiments demonstrating how to scale capability-based design in Scala 3 for different architectural patterns.

## Experiments

### 1. ManyServices.scala - Capability Grouping Strategies

**Purpose**: Demonstrates how to handle many capabilities (10+) in method signatures and provides solutions for better composition.

**Key Findings**:

#### Problem: Individual Capabilities Don't Scale
```scala
// ❌ UNUSABLE: 10 capabilities in signature (7+ lines of code)
def operation(userId: String): (
  Database,
  Cache,
  FileStorage,
  EmailService,
  SmsService,
  PushNotification,
  Logging,
  Metrics,
  Analytics,
  Tracing
) ?=> Unit
```

**Issues**:
- Hard to read and maintain
- High repetition across functions
- Testing requires mocking all 10 capabilities
- Changes propagate everywhere

#### Solution 1: Layered Grouping (RECOMMENDED)
```scala
// ✅ BETTER: Group by architectural layer (3 capabilities)
case class InfraLayer(db: Database, cache: Cache, storage: FileStorage)
case class CommLayer(email: EmailService, sms: SmsService, push: PushNotification)
case class ObsLayer(logging: Logging, metrics: Metrics, analytics: Analytics, tracing: Tracing)

def operation(userId: String): (InfraLayer, CommLayer, ObsLayer) ?=> Unit
```

**Benefits**:
- ✅ Short, readable signatures (1 line)
- ✅ Operations declare only needed layers
- ✅ Easy to test (only mock needed layers)
- ✅ Clear separation of concerns
- ✅ Changes isolated to layer definitions

#### Solution 2: System Wrapper (For Small Apps)
```scala
// ✅ SIMPLEST: Everything in one capability
case class AppSystem(
  db: Database, cache: Cache, storage: FileStorage,
  email: EmailService, sms: SmsService, push: PushNotification,
  logging: Logging, metrics: Metrics, analytics: Analytics, tracing: Tracing
)

def operation(userId: String): AppSystem ?=> Unit
```

**Trade-offs**:
- ✅ Minimal signature
- ❌ Loses granularity (all operations get all capabilities)
- ❌ Always need complete system for testing

#### Solution 3: Hybrid Approach (PRODUCTION)
```scala
// Small, focused operations
def validateOrder(order: Order): InfraLayer ?=> Boolean
def notifyCustomer(order: Order, user: User): CommLayer ?=> Unit
def recordMetrics(order: Order): ObsLayer ?=> Unit

// Compose into larger workflows
def processOrder(order: Order, user: User): (InfraLayer, CommLayer, ObsLayer) ?=> Unit =
  if validateOrder(order) then
    notifyCustomer(order, user)
    recordMetrics(order)
```

**Benefits**:
- ✅ Best of both worlds
- ✅ Fine-grained business logic
- ✅ Flexible composition
- ✅ Test at any granularity

---

### 2. BoundedContexts.scala - Microservices Architecture

**Purpose**: Demonstrates Domain-Driven Design (DDD) bounded contexts with capability-based design for microservices architectures.

**Architecture Overview**:

```
┌─────────────────────────────────────────────────────────────┐
│                 Shared Infrastructure                        │
│  (EventBus, MessageQueue, Logger)                           │
└─────────────────────────────────────────────────────────────┘
         │                    │                    │
         ▼                    ▼                    ▼
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│  Order Context   │  │ Payment Context  │  │ Shipping Context │
├──────────────────┤  ├──────────────────┤  ├──────────────────┤
│ - OrderRepo      │  │ - PaymentGateway │  │ - ShippingService│
│ - OrderValidator │  │ - PaymentRepo    │  │ - ShipmentRepo   │
│                  │  │                  │  │                  │
│ Use Cases:       │  │ Use Cases:       │  │ Use Cases:       │
│ - createOrder    │  │ - processPayment │  │ - createShipment │
│ - confirmOrder   │  │ - refund         │  │ - trackShipment  │
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

#### Key Patterns

**1. Context Capabilities Bundle**
```scala
// Each bounded context bundles its capabilities
case class OrderContextCapabilities(
  repository: OrderRepository,
  validator: OrderValidator,
  eventBus: EventBus,
  logger: Logger
)
```

**2. Context Isolation**
```scala
object OrderContext:
  // Domain models
  case class Order(id: String, customerId: String, items: List[String], ...)

  // Capabilities (traits + accessor objects)
  trait OrderRepository
  object OrderRepository

  // Use cases
  object OrderUseCases:
    def createOrder(...): OrderContextCapabilities ?=> Either[String, Order]
```

**3. Cross-Context Saga Pattern**
```scala
def fulfillOrder(...): (
  OrderContext.OrderContextCapabilities,
  PaymentContext.PaymentContextCapabilities,
  ShippingContext.ShippingContextCapabilities
) ?=> FulfillmentResult =
  // Step 1: Create Order
  // Step 2: Process Payment
  // Step 3: Confirm Order
  // Step 4: Create Shipment
  // Compensating transactions on failure
```

#### Benefits of This Approach

1. **Bounded Context Isolation**
   - Each context has its own capabilities and models
   - Order, Payment, and Shipping are independent
   - Changes in one context don't affect others

2. **Clear Boundaries**
   - Context capabilities make dependencies explicit
   - Easy to identify what each context needs
   - Prevents accidental coupling

3. **Event-Driven Communication**
   - Contexts communicate via EventBus
   - Loose coupling between contexts
   - Support for async communication

4. **Saga Pattern for Distributed Transactions**
   - Coordinates multiple contexts
   - Handles failures gracefully
   - Compensating transactions maintain consistency

5. **Testing Benefits**
   - Test each context independently
   - Mock only needed contexts for saga testing
   - Clear boundaries simplify integration tests

---

## Running the Experiments

```bash
# Run capability scaling experiment
sbt "runMain scaling.scalingMain"

# Run bounded contexts microservices demo
sbt "runMain scaling.boundedContextsMain"
```

---

## Key Takeaways

### For Monolithic Applications
- Use **Layered Grouping** for 6-10+ capabilities
- Group by architectural layer (Infra, Communication, Observability)
- Use **Hybrid Approach** for production code

### For Microservices Architecture
- Model each microservice as a **Bounded Context**
- Bundle context capabilities together
- Use **Saga Pattern** for cross-context operations
- Communicate via **Events** for loose coupling

### General Principles
1. **Start with tuples for 2-3 capabilities**
   ```scala
   def operation(): (Database, Cache) ?=> Unit
   ```

2. **Switch to case classes at 4+ capabilities**
   ```scala
   case class InfraLayer(db: Database, cache: Cache, storage: FileStorage, ...)
   ```

3. **Group by architectural layer or bounded context**
   - Infrastructure Layer (db, cache, storage)
   - Communication Layer (email, sms, push)
   - Observability Layer (logging, metrics, analytics, tracing)
   - Domain Context (OrderContext, PaymentContext, ShippingContext)

4. **Compose small operations into larger workflows**
   - Keep individual operations focused
   - Declare only needed capabilities
   - Compose at the edge (main, controllers, sagas)

---

## Comparison with README.md Examples

The experiments in this directory demonstrate the **practical application** of concepts from the main README.md:

| README Pattern | Scaling Example | Production Use Case |
|----------------|-----------------|---------------------|
| Service Pattern | OrderRepository, PaymentGateway | Infrastructure boundaries |
| Use Case Pattern | OrderUseCases, PaymentUseCases | Business logic |
| Layered Grouping | InfraLayer, CommLayer, ObsLayer | Monolithic apps with many services |
| Bounded Contexts | OrderContext, PaymentContext | Microservices architecture |
| Saga Pattern | OrderFulfillmentSaga | Distributed transactions |

---

## Next Steps

1. **Extend with Real Implementations**
   - Replace mocks with actual database connections
   - Integrate real message queues (Kafka, RabbitMQ)
   - Add real payment gateways

2. **Add Error Handling**
   - Use ZIO or Cats Effect for typed errors
   - Implement retry logic
   - Add circuit breakers

3. **Add Observability**
   - Integrate with OpenTelemetry
   - Add distributed tracing
   - Implement health checks

4. **Add Testing Examples**
   - Unit tests for individual use cases
   - Integration tests for sagas
   - Contract tests between contexts

---

**Author**: David Lebl
**Last Updated**: 2025-11-19