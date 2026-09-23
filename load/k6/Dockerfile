# k6 image with the SSE client extension for the messaging smoke
# (load/k6/messaging.smoke.js reads the realtime stream — SC-005/SC-007).
#
# Stock grafana/k6 cannot consume text/event-stream: the http module buffers
# the whole body before returning it to the script (grafana/k6#746). The
# community extension phymbert/xk6-sse (k6/x/sse) adds a streaming SSE client;
# k6's automatic extension resolution does not (yet) cover it, so the binary
# is built with xk6 and layered onto the stock image.
#
# Build:  docker build -t webchat-k6 load/k6
# Run:    docker run --rm -i --network host webchat-k6 run - < load/k6/messaging.smoke.js
FROM docker.io/grafana/xk6:1.4.14 AS build
# xk6 resolves the extension to its latest published version (v0.2.0 today);
# pinning the extension version is not accepted by xk6's go module resolution.
RUN xk6 build v2.3.0 --with github.com/phymbert/xk6-sse --output /xk6/k6

FROM docker.io/grafana/k6:2.3.0
COPY --from=build /xk6/k6 /usr/bin/k6
