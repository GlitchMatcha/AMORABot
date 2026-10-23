const MAX_USER_DEFINED_BYTES = 256;
const HEX = /^[0-9a-f]*$/i;
const ZERO_PADDING = "0".repeat(32);

/**
 * Splits the `userContextData` Self's relayer forwards: `bytes32 chainId || bytes32 userId || utf8 userDefinedData`,
 * hex encoded. The proof commits to the hash of exactly these bytes, so whatever we read here is what the phone signed
 * (the SDK re-checks that hash). Returns null for anything that is not shaped like a UUID user id plus short data.
 */
export function parseUserContext(userContextData) {
  if (typeof userContextData !== "string" || !HEX.test(userContextData) || userContextData.length % 2 !== 0) return null;
  if (userContextData.length < 128 || userContextData.length > 128 + MAX_USER_DEFINED_BYTES * 2) return null;
  if (userContextData.slice(64, 96) !== ZERO_PADDING) return null; // a UUID occupies the low 16 bytes of the word

  const hex = userContextData.slice(96, 128).toLowerCase();
  const userId = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  const userDefinedData = Buffer.from(userContextData.slice(128), "hex").toString("utf8");
  return { userId, userDefinedData };
}
