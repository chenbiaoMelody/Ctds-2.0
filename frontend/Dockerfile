# C-TDS frontend container (WBS 2.5.1, spec: ADR-013).
# Packaging recipe only: `dist/` is built OUTSIDE the image (local
# `npm run build` or nightly N4, WBS 2.2.6) and must exist before
# `docker build`.
FROM nginx:1.29-alpine

COPY nginx.container.conf /etc/nginx/conf.d/default.conf

COPY dist/ /usr/share/nginx/html

EXPOSE 80
