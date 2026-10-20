FROM node:22-alpine AS verify
WORKDIR /app
COPY package.json ./
COPY src ./src
COPY test ./test
RUN node --test

FROM node:22-alpine
ENV NODE_ENV=production HOST=0.0.0.0 PORT=8787
WORKDIR /app
COPY --from=verify --chown=node:node /app/package.json ./
COPY --from=verify --chown=node:node /app/src ./src
USER node
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD ["node", "src/healthcheck.mjs"]
CMD ["node", "src/server.mjs"]
