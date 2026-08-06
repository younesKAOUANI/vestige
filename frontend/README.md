# Vestige — frontend

React 19 + TypeScript + Vite + TanStack Query. See the [repository README](../README.md#frontend)
for what this dashboard shows and how it fits into the rest of Vestige, and
[Running it locally](../README.md#running-it-locally) for how to run it against
the API.

```bash
npm install
npm run dev      # http://localhost:5173, proxying /api to :8080 (vite.config.ts)
npm run build    # tsc -b (strict) + vite build -> dist/
npm run lint     # oxlint
```

`src/lib/types.ts` and `src/lib/api.ts` are hand-written mirrors of the backend's
response DTOs and REST surface (see [`docs/ARCHITECTURE.md` §8](../docs/ARCHITECTURE.md#8-api-surface)) -
a reasonable follow-up would be generating them from the OpenAPI document Spring
already serves at `/v3/api-docs`, not done here to keep the build free of a codegen
step for a UI this size.
