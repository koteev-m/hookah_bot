rootProject.name = "hookah-bot"

include("backend")
include("backend:app")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
