/// F# stand-in for the Aether slice/resource surface (`org.pragmatica.aether.*`). In the Java
/// project these are real runtime contracts wired by annotation processors; here they carry the
/// same names so every slice reads one-to-one against its Java counterpart. The `@Codec` half of
/// the Java toolchain has no analog on purpose — there is nothing to generate.
namespace Aether

open System
open Pragmatica

/// Marks a use-case module as an Aether slice (Java: `@Slice` on the interface). The Java slice
/// interface has exactly one `execute` method, so in F# the whole contract collapses to a function
/// type — each slice module declares `type Execute = Request -> Promise<Response>` and its factory
/// returns that function.
[<AttributeUsage(AttributeTargets.Class)>]
type SliceAttribute() =
    inherit Attribute()

/// Marks a persistence interface whose implementation is generated and whose queries are validated
/// against the schema at compile time (Java: `@PgSql` + pg-codegen).
[<AttributeUsage(AttributeTargets.Interface)>]
type PgSqlAttribute() =
    inherit Attribute()

/// SQL carried by a store method; named `:params` bind to the method parameters of the same name
/// (Java: `org.pragmatica.aether.pg.codegen.annotation.Query`).
[<AttributeUsage(AttributeTargets.Method)>]
type QueryAttribute(sql: string) =
    inherit Attribute()
    member _.Sql = sql

/// Fact publisher for a pub-sub topic (Java: `Publisher<F>` injected with a topic qualifier).
/// One fact in, acknowledgement out — a function type is the whole contract.
type Publisher<'Fact> = 'Fact -> Promise<unit>

/// Outbound JSON HTTP resource (Java: `@Http HttpClient`).
type HttpClient =
    abstract PostJson<'Request, 'Response> : path: string * body: 'Request -> Promise<'Response>

type NotificationBody = Text of string

type Notification = Email of sender: string * recipients: string list * subject: string * body: NotificationBody

/// Email/SMS resource (Java: `@Notify NotificationSender`).
type NotificationSender =
    abstract Send: Notification -> Promise<unit>
