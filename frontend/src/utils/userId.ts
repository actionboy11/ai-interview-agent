/** UUID identifiers are case-insensitive; preserve their original display values. */
export function isSameUserId(id: string, otherId: string | undefined): boolean {
  return id.trim().toLowerCase() === otherId?.trim().toLowerCase();
}
