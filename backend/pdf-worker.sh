#!/bin/sh
set -eu
exec env -i PATH=/opt/java/openjdk/bin:/usr/bin:/bin \
  /opt/java/openjdk/bin/java -Xmx48m -Xss512k -XX:+UseSerialGC -XX:ActiveProcessorCount=1 \
  -XX:-CreateCoredumpOnCrash -XX:-HeapDumpOnOutOfMemoryError -XX:ErrorFile=/dev/null \
  -XX:-UsePerfData -XX:MaxDirectMemorySize=8m -XX:MaxMetaspaceSize=64m -XX:ReservedCodeCacheSize=32m \
  -cp '/app/app/lib/*' com.hookah.platform.backend.miniapp.venue.media.pdf.PdfWorkerMain "$@"
