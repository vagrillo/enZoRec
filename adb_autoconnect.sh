#!/bin/bash
# Auto-connessione adb wireless via mDNS: nessuna porta da comunicare.
adb start-server >/dev/null 2>&1
echo "Cerco il telefono sulla rete (mDNS)…"
for i in $(seq 1 15); do
  SVC=$(adb mdns services 2>/dev/null | grep -oE '_adb-tls-connect\..*|_adb-tls-connect.*\.[0-9]+' | head -1)
  if [ -n "$SVC" ]; then
    # estrai ip:porta dal servizio (formato: nome._adb-tls-connect._tcp.<ip:porta>)
    ADDR=$(echo "$SVC" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:[0-9]+' | head -1)
    if [ -z "$ADDR" ]; then
      # prova il formato "…._tcp."
      ADDR=$(adb mdns services 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:[0-9]+' | head -1)
    fi
    if [ -n "$ADDR" ]; then
      echo "Trovato: $ADDR — connessione…"
      adb connect "$ADDR" && adb devices | tail -2 && exit 0
    fi
  fi
  # Se c'era già un device connesso in precedenza, riprova quello.
  OLD=$(adb devices | grep -v List | grep "device$" | awk '{print $1}' | head -1)
  [ -n "$OLD" ] && adb connect "$OLD" >/dev/null 2>&1
  sleep 2
done
echo "Non trovato. Sul telefono: riapri 'Debug wireless' (la porta ruota) o usa USB una volta."
exit 1
