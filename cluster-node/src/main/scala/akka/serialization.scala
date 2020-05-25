package bmaso.akka

/**
 * Marker trait to apply to Akka message classes to indicated they should be serialized with Jackson CBOR serialization.
 * Note that the following is needed to get Jackson CBOR serialization working correctly:
 *
 * 1. Add the following dependency in your project:
 * ```
 * # Sbt
 * libraryDependencies += "com.typesafe.akka" %% "akka-serialization-jackson" % "2.6.5"
 *
 * # Maven
 * <dependency>
 *   <groupId>com.typesafe.akka</groupId>
 *   <artifactId>akka-serialization-jackson_2.12</artifactId>
 *   <version>2.6.5</version>
 * </dependency>
 *
 * # Gradle
 * dependencies {
 *   compile group: 'com.typesafe.akka', name: 'akka-serialization-jackson_2.12', version: '2.6.5'
 * }
 * ```
 * 2. Add the following binding to application.conf:
 *
 * ```
 * akka {
 *   actor {
 *     serializers {
 *       jackson-cbor = "akka.serialization.jackson.JacksonCborSerializer"
 *     }
 *     serialization-bindings {
 *       "bmaso.akka.CborSerialization" = jackson-cbor
 *     }
 *   }
 * }
 * ```
 *
 * 3. Extend the `bmaso.akka.CborSerialization` trait in your serialized command & event classes
 *
 **/
trait CborSerialization

/**
 * Marker trait to apply to Akka message classes to indicated they should be serialized with Jackson JSON serialization.
 * Note that the following is needed to get Jackson JSON serialization working correctly:
 *
 * 1. Add the following dependency in your project:
 * ```
 * # Sbt
 * libraryDependencies += "com.typesafe.akka" %% "akka-serialization-jackson" % "2.6.5"
 *
 * # Maven
 * <dependency>
 *   <groupId>com.typesafe.akka</groupId>
 *   <artifactId>akka-serialization-jackson_2.12</artifactId>
 *   <version>2.6.5</version>
 * </dependency>
 *
 * # Gradle
 * dependencies {
 *   compile group: 'com.typesafe.akka', name: 'akka-serialization-jackson_2.12', version: '2.6.5'
 * }
 * ```
 * 2. Add the following binding to application.conf:
 *
 * ```
 * akka {
 *   actor {
 *     serializers {
 *       jackson-json = "akka.serialization.jackson.JacksonJsonSerializer"
 *     }
 *     serialization-bindings {
 *       "bmaso.akka.JsonSerialization" = jackson-json
 *     }
 *   }
 * }
 * ```
 *
 * 3. Extend the `bmaso.akka.JsonSerialization` trait in your serialized command & event classes
 *
 **/
trait JsonSerialization
