package constants

sealed class Status<T> (open val data: T) {
    class Success<T> (override val data: T): Status<T>(data)
    class Error<T> (override val data: T, val error: ErrorType): Status<T>(data)
}