package constants

enum class ReleaseType {
    RELEASE, DEBUG;

    val isRelease: Boolean
        get() = this == RELEASE
    val isDebug: Boolean
        get() = this == DEBUG
}