export function getBackendBaseUrl(): string {
  return import.meta.env.VITE_BACKEND_PUBLIC_URL ?? 'http://localhost:8080'
}
