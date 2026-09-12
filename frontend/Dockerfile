# C-TDS frontend container (WBS 2.5.2 non-root hardening, spec ADR-013 as
# amended by WBS-2.5.2). Packaging recipe only: `dist/` is built OUTSIDE the
# image (local `npm run build` or nightly N4, WBS 2.2.6) and must exist
# before `docker build`.
FROM nginx:1.29-alpine

# Full main config: non-root safe paths (pid/temp under /tmp, logs on
# stdio). The server block stays in conf.d/default.conf, listen 8080.
COPY nginx.main.conf /etc/nginx/nginx.conf

COPY nginx.container.conf /etc/nginx/conf.d/default.conf

COPY dist/ /usr/share/nginx/html

EXPOSE 8080

# Non-root: uid 101 = the image's built-in `nginx` user (review-2 finding,
# WBS 2.5.1). No new base image, no new dependency.
USER 101
