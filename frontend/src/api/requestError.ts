/** A parsed server Result error; transport failures deliberately retain plain Error. */
export class ResultError extends Error {
  readonly code: number;

  constructor(code: number, message: string) {
    super(message);
    this.name = 'ResultError';
    this.code = code;
  }
}
