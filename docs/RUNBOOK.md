# AGENTPLANE — Troubleshooting Runbook

Every entry here follows the same shape: **symptom → how to confirm → root cause → fix**.

Most of these were hit while building this system rather than collected from articles. Where the
output below is a real capture from this codebase, it says so. Where an entry describes a failure
mode that was reasoned through rather than reproduced here, it says that too — a runbook that
overstates its evidence is worse than one that admits its gaps, because you find out which at 3am.

---

## 1. `CrashLoopBackOff`

**Symptom** — pod restarts repeatedly, `RESTARTS` climbs, never reaches `Ready`.

**Confirm**
```bash
kubectl get pod <pod> -o wide
kubectl describe pod <pod> | sed -n '/State:/,/Ready/p'
kubectl logs <pod> --previous          # the crashed container, not the current one
```
`--previous` is the important flag. Without it you read the logs of a container that has not failed
yet and see nothing wrong.

**Root cause — four causes that look identical from `kubectl get pods`.** Tell them apart by exit code:

| Exit code | Meaning | Where to look next |
|---:|---|---|
| **1** | Application threw during startup | `kubectl logs --previous` — usually a Spring context failure |
| **137** | SIGKILL — almost always OOMKilled | `kubectl describe pod`, look for `Reason: OOMKilled` (see §2) |
| **143** | SIGTERM — the pod was told to stop; it did not crash | Usually a failing liveness probe (see §6) |
| **0** | The process exited cleanly | Wrong entrypoint, or a batch command in a Deployment |

For AGENTPLANE specifically, exit 1 during startup is nearly always one of:
- Flyway cannot reach Postgres (`Unable to obtain connection from database`)
- `ddl-auto: validate` found a mapping the schema does not have
- A required bean is missing

**Fix** — read the first `Caused by:` in the `--previous` logs. Spring's failure analyser prints a
`Description:` and an `Action:` block; that block is usually the whole answer.

> **Hit while building this.** The control plane crash-looped on start with
> `Parameter 3 of constructor in ClaimService required a bean of type '...$AuditEventRepository'
> that could not be found`. Cause: the repository interfaces had been written as **nested**
> interfaces inside a container class. Spring Data's scanner does not register nested repository
> interfaces. Fix: promote each to a top-level interface. Nothing about the code looked wrong —
> it compiled cleanly, and the bean simply never existed.

---

## 2. `OOMKilled` (exit 137) — and why the JVM causes it

**Symptom** — pod killed abruptly under load. No exception, no stack trace, no shutdown log.

**Confirm**
```bash
kubectl describe pod <pod> | grep -A3 'Last State'
#   Last State:  Terminated
#     Reason:    OOMKilled
#     Exit Code: 137
```

**This is not the same as an application `OutOfMemoryError`.** Tell them apart:

| | Container OOMKill (137) | Application `OutOfMemoryError` |
|---|---|---|
| Who killed it | The kernel cgroup limiter | The JVM itself |
| Java stack trace | **None** — process vanishes | Yes, `java.lang.OutOfMemoryError` |
| Meaning | Total RSS exceeded the container limit | Heap exhausted within its own limit |
| Usual fix | Size the heap relative to the limit | Fix the leak, or raise the heap |

A container OOMKill with no Java stack trace almost always means the JVM's heap ceiling was set
higher than the container's memory limit — so the JVM was still happily allocating when the kernel
killed it.

**Root cause — the JVM's default heap is a fraction of what it *thinks* the machine has.**

Measured on this machine (host with 15 GiB RAM, JDK 21):

```
$ java -XX:+PrintFlagsFinal -version | grep MaxHeapSize
   MaxHeapSize    3581935616        # 3.5 GiB — 25% of the 15 GiB host

$ java -XX:MaxRAM=512m -XX:+PrintFlagsFinal -version | grep MaxHeapSize
   MaxHeapSize     134217728        # 128 MiB — 25% of a 512 MiB limit

$ java -XX:MaxRAM=512m -XX:MaxRAMPercentage=75 -XX:+PrintFlagsFinal -version | grep MaxHeapSize
   MaxHeapSize     402653184        # 384 MiB — 75% of the limit
```

Two distinct failures fall out of that, and they are opposite:

1. **The JVM cannot see the limit.** On JDK 8 before 8u191, or with `-XX:-UseContainerSupport`, the
   JVM reads the *host's* memory. In a 512 MiB container on a 15 GiB node it sets a **3.5 GiB**
   heap and gets OOMKilled the moment it grows past 512 MiB. This is the classic one, and it is
   silent: nothing in the JVM logs says "I am about to ask for more than I am allowed".
2. **The JVM can see the limit and is too conservative.** A modern JVM reads the cgroup and
   defaults to **25%** — 128 MiB of a 512 MiB container. It will not be OOMKilled, but it wastes
   384 MiB and starts GC-thrashing early, which reads like a performance problem rather than a
   configuration one.

**Fix** — set the heap as a *percentage of the container limit*, never as a fixed `-Xmx` that
someone must remember to change when the limit changes:

```dockerfile
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
```

**Why 75 and not 100.** The heap is not the process. The JVM also needs metaspace, thread stacks
(~1 MiB each — a 200-thread Tomcat pool is 200 MiB), code cache, GC structures and direct byte
buffers. Those live *outside* the heap and still count toward the cgroup limit. 75% leaves room;
90% gets you OOMKilled by native memory while the heap graph looks healthy, which is a genuinely
confusing afternoon.

**Why G1 deliberately.** Below ~256 MiB heap, SerialGC has lower overhead and the JVM picks it
automatically. Above that, G1 is chosen here because its pause targets matter more than throughput
for a control plane serving SSE connections — a long stop-the-world pause shows up as every
connected console freezing at once.

`-XX:+ExitOnOutOfMemoryError` matters too: without it a heap-exhausted JVM limps on, failing
requests while still passing its liveness probe. Better to die and be restarted.

---

## 3. `ImagePullBackOff` — registry auth vs. bad tag

**Symptom** — pod stuck `Pending`/`ImagePullBackOff`, never starts.

**Confirm**
```bash
kubectl describe pod <pod> | grep -A5 Events
```
The message distinguishes the two causes precisely:

| Message | Cause | Fix |
|---|---|---|
| `manifest for ...:tag not found` | The tag does not exist | Wrong tag, or the CI push never happened. Check the registry. |
| `unauthorized` / `authentication required` | Credentials | Missing/expired `imagePullSecrets`, or the node's identity lacks pull rights |
| `x509: certificate signed by unknown authority` | Registry TLS | Private registry CA not trusted by the node (§4) |
| `no match for platform` | Architecture | An amd64 image on an arm64 node |

**Fix for auth on AKS** — attach the registry to the cluster identity rather than shipping a pull
secret:
```bash
az aks update -n <cluster> -g <rg> --attach-acr <registry>
```

> **Hit while building this.** Pulling `postgres:16-alpine` failed with
> `failed to copy: httpReadSeeker: failed open: unexpected status ... 403 Forbidden` from a
> CloudFront blob URL. Not an auth problem and not a bad tag — the environment's egress proxy
> permitted the registry API but not the blob CDN it redirects to. Worth knowing as a category:
> **a pull can fail on the layer fetch even after the manifest resolves**, and the fix is network
> policy, not credentials. Local Postgres was installed from the OS package repository instead.

---

## 4. `PKIX path building failed` — keystore vs truststore

**Symptom**
```
javax.net.ssl.SSLHandshakeException: PKIX path building failed:
  sun.security.provider.certpath.SunCertPathBuilderException:
  unable to find valid certification path to requested target
```

*(Real capture, reproduced deliberately against this JDK with an empty truststore.)*

**Confirm** — what the client actually trusts:
```bash
keytool -list -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit | grep -ci 'trustedCertEntry'
keytool -list -keystore /path/to/truststore.p12 -storepass "$PW"

# what the server actually presents, including the chain it sends
openssl s_client -connect control-plane.agentplane.svc:8443 -showcerts </dev/null
```

**Root cause — the single most useful distinction, and the one people get backwards:**

| | **Keystore** | **Truststore** |
|---|---|---|
| Holds | *My* certificate **and private key** | *Other people's* CA certificates |
| Answers | "who am I?" | "whom do I believe?" |
| Used when | Proving my identity (server TLS, or client cert in mTLS) | Validating the peer's certificate |
| Flag | `-Djavax.net.ssl.keyStore` | `-Djavax.net.ssl.trustStore` |

**`PKIX path building failed` is always a truststore problem.** The JVM was asked to validate a
certificate and could not build a chain from it to anything it trusts. Adding your certificate to
the *keystore* — a very common reflex — changes nothing.

Three real variants:
1. **CA missing from the truststore.** With cert-manager's self-signed `ClusterIssuer`, the CA is
   generated in-cluster and is not in any public trust store. Import it.
2. **Incomplete chain from the server.** The server sends its leaf but not the intermediate. The
   client cannot bridge the gap. Fix the *server* — send the full chain — rather than pinning the
   intermediate on every client.
3. **Setting `trustStore` clobbers the defaults.** `-Djavax.net.ssl.trustStore=/my/store.p12`
   *replaces* `cacerts`; it does not add to it. Every previously working public TLS call now fails.
   The reproduction above is exactly this case. Import your CA **into a copy of `cacerts`**:
   ```bash
   cp "$JAVA_HOME/lib/security/cacerts" /app/truststore.p12
   keytool -importcert -alias agentplane-ca -file ca.crt \
           -keystore /app/truststore.p12 -storepass changeit -noprompt
   ```

**Diagnosing which side is wrong:**
```bash
java -Djavax.net.debug=ssl:handshake:trustmanager -jar app.jar 2>&1 | head -100
```
`trustmanager` prints the trust anchors it considered — if your CA is not in that list, it was
never loaded, and the problem is the truststore path or password, not the certificate.

**In mTLS, this error can come from either direction.** The control plane validating the worker, or
the worker validating the control plane. Read *which* JVM logged it before changing anything.

---

## 5. Slow DNS resolution and `ndots`

**Symptom** — every outbound call carries an extra 10–50 ms. No single component looks slow;
everything is slightly slow, which is much harder to notice.

**Confirm**
```bash
kubectl exec -it <pod> -- cat /etc/resolv.conf
# search agentplane.svc.cluster.local svc.cluster.local cluster.local
# options ndots:5

kubectl exec -it <pod> -- sh -c 'time nslookup postgres.database.azure.com'
```

**Root cause.** `ndots:5` is the Kubernetes default. It means: *if a hostname has fewer than 5
dots, try each search domain first before treating it as absolute.*

`postgres.database.azure.com` has 3 dots. So the resolver tries:
```
postgres.database.azure.com.agentplane.svc.cluster.local   → NXDOMAIN
postgres.database.azure.com.svc.cluster.local              → NXDOMAIN
postgres.database.azure.com.cluster.local                  → NXDOMAIN
postgres.database.azure.com                                → finally resolves
```
Four lookups instead of one, each a round trip to CoreDNS, and on every new connection. Under load
this is also a meaningful amount of CoreDNS traffic.

**Fix — two options, and they are not equivalent:**

```yaml
# Option A: lower ndots. In-cluster short names with 2+ dots still work.
dnsConfig:
  options:
    - name: ndots
      value: "2"
```
```yaml
# Option B: fully-qualify external hostnames with a trailing dot.
SPRING_DATASOURCE_URL: jdbc:postgresql://postgres.database.azure.com./agentplane
```
The trailing dot marks the name absolute and skips the search list entirely. It is surgical, but it
looks like a typo and someone will "fix" it — comment it where it appears.

**Caution with option A.** Lowering `ndots` breaks single-label in-cluster lookups: `redis` on its
own has 0 dots and still needs the search list. `redis.agentplane.svc.cluster.local` is unaffected.
Prefer fully-qualified service names in configuration, then `ndots:2` is safe.

*Not reproduced here — no cluster available in this environment. The mechanism is
`resolv.conf` behaviour and is verifiable with `nslookup` inside any pod.*

---

## 6. Readiness failing while liveness passes

**Symptom** — pod is `Running` but `READY 0/1`. It never receives traffic and never restarts. It
just sits there, which is the most confusing possible outcome.

**Confirm**
```bash
kubectl get pod <pod>                    # Running, 0/1
kubectl describe pod <pod> | grep -A5 'Readiness probe failed'
kubectl exec <pod> -- curl -s localhost:8080/actuator/health/readiness
```

**Root cause.** The two probes answer different questions and are wired to different endpoints:

| Probe | Question | Endpoint | On failure |
|---|---|---|---|
| **Liveness** | Is the process wedged? | `/actuator/health/liveness` | Kill and restart |
| **Readiness** | Can it serve *right now*? | `/actuator/health/readiness` | Remove from Service endpoints |

Liveness passing while readiness fails means the process is fine but a **dependency** is not.
Spring Boot's readiness group includes the datasource, Mongo and Redis health indicators, so any of
those being down takes the pod out of rotation while correctly leaving it running.

Check, in order: Postgres reachable? Mongo reachable? Redis reachable? Connection pool exhausted
(§7)?

**The configuration mistake that turns this into an outage:** pointing *liveness* at a check that
includes dependencies. When the database has a brief hiccup, every pod fails liveness, Kubernetes
kills every pod simultaneously, and a recoverable database blip becomes a full restart storm that
outlasts it.

**Liveness must only ask "is this process wedged?"** — never "are my dependencies healthy?"

```yaml
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: 8080 }
  failureThreshold: 6
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: 8080 }
  failureThreshold: 2          # drop out of rotation quickly
startupProbe:
  httpGet: { path: /actuator/health/liveness, port: 8080 }
  failureThreshold: 30
  periodSeconds: 5             # 150s budget for JVM start + Flyway, before liveness applies
```
The `startupProbe` is what stops a slow JVM boot from being mistaken for a hang and killed in a loop.

---

## 7. Postgres connection pool exhaustion

**Symptom**
```
java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available,
request timed out after 30000ms
```
Latency climbs, then requests fail. The database itself looks idle — low CPU, few active queries —
which sends people to the wrong place.

**Confirm — application side:**
```bash
curl -s localhost:8080/actuator/metrics/hikaricp.connections.active
curl -s localhost:8080/actuator/metrics/hikaricp.connections.pending
```
`pending > 0` sustained means threads are queuing for a connection.

**Confirm — database side.** This is the query that actually answers the question:
```sql
-- Who holds a connection, and what are they doing with it?
SELECT pid,
       state,
       wait_event_type,
       wait_event,
       now() - state_change AS idle_for,
       now() - xact_start   AS xact_age,
       left(query, 80)      AS query
FROM pg_stat_activity
WHERE datname = 'agentplane'
ORDER BY xact_age DESC NULLS LAST;
```

Read `state`:
- **`idle in transaction`** — the smoking gun. A transaction was opened and never committed or
  rolled back. The connection is held and unusable by anyone else.
- **`active` with a long `xact_age`** — a genuinely slow query. Different problem: go to `EXPLAIN`.
- **`idle`** — connections are free; the pool is simply too small, or the leak is elsewhere.

```sql
-- Aggregate view
SELECT state, count(*), max(now() - state_change) AS longest
FROM pg_stat_activity WHERE datname = 'agentplane' GROUP BY state;
```

**Root causes, in the order they actually occur:**
1. **`idle in transaction`** — a transaction opened outside a `@Transactional` boundary and never
   closed. In AGENTPLANE the guard against this is that `@Transactional` lives only on service
   classes, and `spring.jpa.open-in-view` is **false** — leaving it on holds a connection for the
   entire HTTP request, including response serialisation.
2. **Pool smaller than the concurrency.** With `maximum-pool-size: 10` and 200 Tomcat threads, 190
   threads can queue behind 10 connections.
3. **Long-running work holding a connection.** An SSE endpoint or a Kubernetes watch that keeps a
   transaction open for its lifetime will exhaust a pool with very little traffic.
4. **Pool total across replicas exceeds the server's `max_connections`.** 10 replicas × 20
   connections = 200, against an Azure Flexible Server allowing 100. Each pod looks fine in
   isolation; the cluster does not.

**Fix**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      leak-detection-threshold: 60000   # log a stack trace for a connection held >60s
      max-lifetime: 900000              # shorter than the server's idle timeout
```
`leak-detection-threshold` is the one to reach for first: it prints the **stack trace of the code
that took the connection and did not return it**, which turns a guessing game into a line number.

Terminate a stuck session only after capturing what it was doing:
```sql
SELECT pg_terminate_backend(pid) FROM pg_stat_activity
WHERE datname='agentplane' AND state='idle in transaction'
  AND now() - state_change > interval '10 minutes';
```

---

## 8. Consumer group lag — and a worker that dies mid-run

**Symptom** — runs sit in `SCHEDULED` and never progress. The stream keeps growing. Workers look
healthy.

**Confirm** — this is a real capture against the Redis instance used to develop this:

```
$ redis-cli XPENDING agentplane:runs workers
3                       # 3 messages delivered but never acknowledged
1787772733491-0         # oldest pending id
1787772733501-0         # newest pending id
worker-1  3             # all 3 owned by worker-1

$ redis-cli XINFO GROUPS agentplane:runs
consumers          1
pending            3
last-delivered-id  1787772733501-0
entries-read       5
lag                0
```

Read those two numbers precisely, because they mean different things:

- **`lag`** — entries added to the stream that this group has **never been delivered**. `lag > 0`
  means not enough consumers, or consumers not reading.
- **`pending`** — entries **delivered but not acknowledged**. `pending > 0` with `lag: 0` means
  the messages *were* picked up and the worker never finished them.

In the capture above `lag: 0, pending: 3` — the work was claimed and then abandoned. That is the
"worker died mid-run" signature, and it is invisible if you only watch stream length.

**Root cause.** Redis Streams hand a message to a consumer and hold it in that consumer's Pending
Entries List until `XACK`. If the worker dies between reading and acknowledging, the message is
**owned by a consumer that no longer exists**. It is not lost, and it is also not redelivered on
its own. Without a reclaim step it sits there forever.

**Fix — reclaim stale entries.** Real capture, reassigning the abandoned messages to a live worker:
```
$ redis-cli XAUTOCLAIM agentplane:runs workers worker-2 0 0 COUNT 10
0-0                     # next cursor
1787772733491-0  runId run-3  tenant acme
1787772733496-0  runId run-4  tenant acme
...
```
`AGENTPLANE` runs this on a schedule with a non-zero min-idle-time (a *visibility timeout*): only
entries idle longer than that are reclaimed, so a slow-but-alive worker is not robbed of its work.

**Why reclaiming is safe here.** Redelivery means a run could be launched twice. Every run carries
an **idempotency key** guarded by a Redis `SETNX`; the second attempt sees the key and becomes a
no-op instead of starting a second Kubernetes Job. Reclaim without idempotency is how one crash
turns into two charges to a customer's token budget.

**Choosing the visibility timeout:** longer than the slowest legitimate run, or you reclaim work
that is still in progress. Shorter than a user will tolerate waiting after a crash. AGENTPLANE ties
it to the run's own `timeoutSeconds` rather than using one global value.

---

## Quick triage

| Symptom | First command |
|---|---|
| Pod restarting | `kubectl logs <pod> --previous` |
| Exit code 137 | `kubectl describe pod <pod> \| grep -A3 'Last State'` |
| Pod `Running` but `0/1` | `kubectl exec <pod> -- curl -s localhost:8080/actuator/health/readiness` |
| `PKIX path building failed` | `keytool -list -keystore <truststore>` — is the CA there at all? |
| Everything slightly slow | `kubectl exec <pod> -- cat /etc/resolv.conf` — check `ndots` |
| Requests timing out, DB idle | `SELECT state, count(*) FROM pg_stat_activity GROUP BY state;` |
| Runs stuck in SCHEDULED | `redis-cli XPENDING agentplane:runs workers` |
| Image will not pull | `kubectl describe pod <pod> \| grep -A5 Events` |
