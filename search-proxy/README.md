# Floryx search proxy

Deploy with Cloudflare Workers, then store the Tavily key as a Worker secret:

```powershell
npx wrangler@4.36.0 login
npx wrangler@4.36.0 deploy
npx wrangler@4.36.0 secret put TAVILY_API_KEY
```

Build the Android app with the deployed URL:

```powershell
.\gradlew.bat :app:assembleRelease -PfloryxSearchProxyUrl=https://floryx-search.<account>.workers.dev
```
