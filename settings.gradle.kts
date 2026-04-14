plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "pokemon-battle"

include(":engine")
include(":data")
include(":render")
include(":ai")
include(":data-ingestion")
include(":cli")
include(":analytics")
include(":server")
