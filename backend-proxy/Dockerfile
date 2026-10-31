# Stage 7: Production Multi-Stage Dockerfile for Google Cloud Run
FROM node:20-slim AS builder
WORKDIR /usr/src/app
COPY package*.json tsconfig.json ./
RUN npm install --ignore-scripts
COPY src ./src
COPY test ./test
RUN npm run build

FROM node:20-slim AS runner
WORKDIR /usr/src/app
ENV NODE_ENV=production
ENV PORT=8080
COPY package*.json ./
RUN npm install --omit=dev --ignore-scripts
COPY --from=builder /usr/src/app/dist ./dist
USER node
EXPOSE 8080
CMD ["node", "dist/src/index.js"]
