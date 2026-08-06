pluginManagement {
    val build_snapshot_train: String? by settings
    repositories {
        maven("https://cache-redirector.jetbrains.com/plugins.gradle.org/m2")
        if (build_snapshot_train?.toBoolean() == true) {
            mavenLocal()
        }
    }
}
