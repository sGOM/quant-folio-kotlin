FROM node:20-slim

WORKDIR /app

# 원본은 lockfile 없이 npm install — 현재 레지스트리에선 의존성 해석이 깨져(edgesOut null) lockfile 고정 설치로 바꿈.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

EXPOSE 3000

CMD ["npm", "run", "dev"]
