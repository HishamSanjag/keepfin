/** An error that becomes an HTTP answer: a status, a stable code and plain words. */
export class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message?: string,
  ) {
    super(message ?? code)
  }
}
