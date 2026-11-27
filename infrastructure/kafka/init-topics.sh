#!/usr/bin/env bash
set -euo pipefail
bootstrap=${KAFKA_BOOTSTRAP_SERVERS:-kafka:19092}
topics=/opt/kafka/bin/kafka-topics.sh
configs=/opt/kafka/bin/kafka-configs.sh
while IFS= read -r topic; do
  [[ -z "$topic" || "$topic" == \#* ]] && continue
  maximum=1048576
  [[ "$topic" == gp.payment.results.dlt.v1 ]] && maximum=4194304
  settings=(min.insync.replicas=1 cleanup.policy=delete retention.ms=604800000 retention.bytes=-1 segment.ms=3600000 max.message.bytes="$maximum")
  args=()
  for setting in "${settings[@]}"; do args+=(--config "$setting"); done
  "$topics" --bootstrap-server "$bootstrap" --create --if-not-exists --topic "$topic" --partitions 3 --replication-factor 1 "${args[@]}"
  description=$("$topics" --bootstrap-server "$bootstrap" --describe --topic "$topic")
  if ! grep -Eq 'PartitionCount: 3[[:space:]]+ReplicationFactor: 1' <<<"$description"; then
    echo "TOPOLOGY_MISMATCH $topic: $description" >&2; exit 1
  fi
  actual=$("$configs" --bootstrap-server "$bootstrap" --entity-type topics --entity-name "$topic" --describe)
  for setting in "${settings[@]}"; do
    if ! grep -Fq "$setting " <<<"$actual"; then
      echo "CONFIG_MISMATCH $topic expected $setting: $actual" >&2; exit 1
    fi
  done
  echo "TOPIC_OK $topic partitions=3 replicas=1"
done < /contract/topics.txt
