package vn.tramtruyen.reader.engine

/** Loi co ma HTTP — tuong duong HTTPException ben FastAPI. */
class ApiError(val status: Int, message: String) : Exception(message)
