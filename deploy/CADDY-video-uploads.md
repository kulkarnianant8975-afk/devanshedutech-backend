# Raising the upload limit for video

`/root/pdma-crm/Caddyfile` currently caps request bodies at 80 MB on the Devansh backend routes:

```
@backend path /api/* /auth/* /oauth2/* /login/oauth2/*
handle @backend {
        request_body {
                max_size 80MB
        }
        reverse_proxy dvt-backend:8080
}
```

A 200 MB video upload is refused there, before it reaches the application — and Caddy's refusal
looks like a network failure rather than a size limit, so it is worth changing deliberately
rather than discovering.

That file is shared by three projects and owns ports 80 and 443 for all of them, so it is not
touched by the deploy workflow. Change it by hand, on both Devansh host blocks
(`www.devanshedutech.com` and `dvt.187-127-190-28.sslip.io`):

```
cp /root/pdma-crm/Caddyfile /root/pdma-crm/Caddyfile.bak-$(date +%Y%m%d-%H%M%S)
sed -i 's/max_size 80MB/max_size 220MB/' /root/pdma-crm/Caddyfile
docker exec pdma-crm-caddy-1 caddy validate --config /etc/caddy/Caddyfile
docker exec pdma-crm-caddy-1 caddy reload --config /etc/caddy/Caddyfile
```

`validate` before `reload`: a Caddyfile that does not parse takes down all three sites, not one.

220 rather than 200, because multipart adds boundary overhead and a limit exactly equal to the
file size rejects the file.
