.PHONY: start stop test lint clean devices

# recorda is host-only and never deployed, so there is no `build` here: no
# uberjar, no fly, no umbrella. `start` is the whole of it.

start:
	@if [ -f .env ]; then set -a && . ./.env && set +a; fi && ./scripts/start.sh

stop:
	./scripts/stop.sh

# What ffmpeg can actually see right now, and which screen recorda would pick.
# Worth running when a display is plugged in or the Scarlett is not found — it
# answers "is it the app or is it the machine" without starting the server.
devices:
	@clojure -M -e "(require '[et.rec.devices :as d]) (d/print-report)"

test:
ifdef NS
	DEV=true clojure -M:test -n $(NS)
else
	DEV=true clj -X:test
endif

lint:
	clj-kondo --lint src/clj

clean:
	rm -rf target node_modules .shadow-cljs resources/public/recorda/js
