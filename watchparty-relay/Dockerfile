# Watch Party relay
#
# Build from the repository root (the remote page uses the browser
# extension's screens, in watchparty-browser/):
#   docker build -f watchparty-relay/Dockerfile -t watchparty-relay .
#   docker run -d --restart unless-stopped -p 8765:8765 watchparty-relay
#
# Fixed rooms instead of open mode:
#   docker run -d -p 8765:8765 -e WATCHPARTY_ROOMS=MOVIE,SHOWS watchparty-relay
FROM python:3.12-alpine

WORKDIR /app
COPY watchparty-relay/relay_standalone.py watchparty-relay/relay.py ./
# the remote page (/remote): its own files and the extension's screens
COPY watchparty-relay/remote/ remote/
COPY watchparty-browser/extension/links.js watchparty-browser/extension/justwatch.js \
     watchparty-browser/extension/hbomax.js watchparty-browser/extension/relay.js \
     watchparty-browser/extension/ui.js watchparty-browser/extension/screens.js \
     watchparty-browser/extension/room.css remote/
COPY watchparty-browser/extension/fonts/ remote/fonts/
COPY watchparty-browser/extension/icons/icon32.png watchparty-browser/extension/icons/icon128.png remote/icons/

# room playback state survives container restarts (mount /data to also
# survive container re-creation)
RUN mkdir -p /data && chown nobody /data
ENV WATCHPARTY_STATE=/data/state.json

EXPOSE 8765
USER nobody

HEALTHCHECK --interval=30s --timeout=5s \
  CMD wget -qO- http://127.0.0.1:8765/ping || exit 1

ENTRYPOINT ["python3", "relay_standalone.py"]
