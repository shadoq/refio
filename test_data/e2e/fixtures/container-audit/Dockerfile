FROM node:latest

ENV NODE_ENV=production
ENV API_KEY=sk-live-9f2c-a41d-prod

WORKDIR /app
COPY . .
USER root

EXPOSE 8080
CMD ["node", "server.js"]
