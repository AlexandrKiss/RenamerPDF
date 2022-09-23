package constants

sealed class FileStatus<T> (open val data: T) {
    class Success<T> (override val data: T): FileStatus<T>(data)
    class Duplicate<T> (override val data: T): FileStatus<T>(data)
    class Error<T> (override val data: T, val error: ErrorType): FileStatus<T>(data)
}
