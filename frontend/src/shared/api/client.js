export class ApiError extends Error {
  constructor(status, body) {
    super(body.message || `Request failed (${status}).`);
    this.status = status;
    this.code = body.code;
    this.details = body.details || [];
  }
}
export async function request(path, { signal, method = 'GET', body, token } = {}) {
  const response = await fetch(path, {
    signal,
    method,
    credentials: 'same-origin',
    headers: {
      Accept: 'application/json',
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { 'X-CSRF-Token': token } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  if (!response.ok) {
    const error = await response
      .json()
      .catch(() => ({ message: `Service unavailable (${response.status}).` }));
    throw new ApiError(response.status, error);
  }
  return response.status === 204 ? null : response.json();
}
