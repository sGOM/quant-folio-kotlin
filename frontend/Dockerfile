FROM node:24-slim AS build

WORKDIR /app

# 원본은 lockfile 없이 npm install — 현재 레지스트리에선 의존성 해석이 깨져(edgesOut null) lockfile 고정 설치로 바꿈.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# NEXT_PUBLIC_* 는 빌드 때 번들에 박힌다. 빈 값 = 상대경로(같은 출처). 정의돼 있어야 lib/api.ts 의 localhost:8000 폴백을 피한다.
ARG NEXT_PUBLIC_API_BASE_URL=
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}
RUN npm run build && npm prune --omit=dev

# 운영은 next start — 원본의 dev 서버(소스 마운트)는 느리고 메모리가 크며 dev 오버레이·소스맵을 노출한다.
FROM node:24-slim
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/package.json /app/next.config.mjs ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/.next ./.next
EXPOSE 3000
CMD ["npm", "run", "start"]
