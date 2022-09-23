FROM ghcr.io/cseelye/ubuntu-base
RUN apt-get update && \
    apt-get install --yes \
        nodejs \
        npm \
    && apt-get autoremove --yes && apt-get clean && rm -rf /var/lib/apt/lists/*
COPY insteon-bridge/package.json /insteon-bridge/
RUN cd /insteon-bridge && npm install

COPY insteon-bridge/insteon-bridge.js /insteon-bridge/

WORKDIR /insteon-bridge
ENTRYPOINT ["node", "insteon-bridge.js"]
CMD []
