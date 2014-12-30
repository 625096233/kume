package org.rakam.kume.service.ringmap;

import org.rakam.kume.Cluster;
import org.rakam.kume.Member;
import org.rakam.kume.MembershipListener;
import org.rakam.kume.MigrationListener;
import org.rakam.kume.OperationContext;
import org.rakam.kume.Request;
import org.rakam.kume.service.PausableService;
import org.rakam.kume.util.ConsistentHashRing;
import org.rakam.kume.util.Throwables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Map.Entry;
import static org.rakam.kume.util.ConsistentHashRing.TokenRange;
import static org.rakam.kume.util.ConsistentHashRing.hash;

public class RingMap<K, V> extends PausableService<RingMap> implements MembershipListener {
    final static Logger LOGGER = LoggerFactory.getLogger(RingMap.class);
    private final MapMergePolicy<V> mergePolicy;
    private final int replicationFactor;

    ConcurrentHashMap<K, V>[] map;
    int[] bucketIds;
    private ConsistentHashRing ring;
    private Random random = new Random();
    int bucketPerNode = 8;

    private final Member localMember;
    private LinkedList<MigrationListener> migrationListeners = new LinkedList<>();
    Map<TokenRange, Map<K, V>> dataWaitingForMigration = new HashMap<>();

    public RingMap(Cluster.ServiceContext<RingMap> serviceContext, MapMergePolicy<V> mergePolicy, int replicationFactor) {
        super(serviceContext);
        this.mergePolicy = mergePolicy;
        this.replicationFactor = replicationFactor;

        Cluster cluster = serviceContext.getCluster();
        cluster.addMembershipListener(this);
        localMember = cluster.getLocalMember();

        // if we're the master node and initializing the service, then it's a new service.
        if(cluster.getMaster().equals(localMember)) {
            ConsistentHashRing newRing = new ConsistentHashRing(cluster.getMembers(), bucketPerNode, replicationFactor);
            ring = newRing;
            bucketIds = createBucketForRing(newRing);
            map = createEmptyMap(ring);
        }else {
            CompletableFuture<ConsistentHashRing> ringFuture = serviceContext.ask(cluster.getMaster(), (service, serviceContext0) -> serviceContext0.reply(service.getRing()));
            ConsistentHashRing ring = ringFuture.join();
            setRing(ring);
        }
    }

    protected int[] createBucketForRing(ConsistentHashRing ring) {
        return ring.getBuckets().entrySet().stream()
                .filter(entry -> entry.getValue().contains(localMember))
                .mapToInt(entry -> entry.getKey().id).sorted().toArray();
    }

    private ConcurrentHashMap[] createEmptyMap(ConsistentHashRing ring) {
        long count = ring.getBuckets().entrySet().stream().filter(x -> x.getValue().contains(localMember)).count();
        return Stream.generate(() -> new ConcurrentHashMap<>()).limit(count)
                .toArray(value -> new ConcurrentHashMap[(int) count]);
    }

    public void logOwnedBuckets() {
        StringBuilder str = new StringBuilder();
        str.append("ownedBuckets[" + map.length + "]: ");
        int i = 0;
        for (ConcurrentHashMap m : map) {
            str.append("[" + m.size() + "]");
            i += m.size();
        }
        str.append(" = " + i);
        LOGGER.debug(str.toString());
    }

    @Override
    public synchronized void memberAdded(Member member) {
        if (ring.getMembers().contains(member)) {
            // it means we joined this cluster already
            // via requesting ring from an existing node.
            return;
        }

        LOGGER.debug("Adding member {} to existing cluster of {} nodes.", member, serviceContext.getCluster().getMembers().size());

        ConsistentHashRing newRing = ring.addNode(member);
        changeRing(newRing).join();
    }

    @Override
    public void clusterChanged() {
        ConsistentHashRing remoteRing = serviceContext
                .ask(serviceContext.getCluster().getMaster(), (service, serviceContext1) -> serviceContext1.reply(service.getRing()), ConsistentHashRing.class)
                .join();

        ConsistentHashRing newRing;
        if (remoteRing.getMembers().contains(localMember)) {
            ring = remoteRing.removeNode(localMember);
            newRing = remoteRing;
        } else {
            ring = remoteRing;
            newRing = remoteRing.addNode(localMember);
        }
        // we don't care about the old entries because the old ring doesn't have this local member so all operations will be remote.
        // the old entries will be added to the cluster when the new ring is set.
        changeRing(newRing).thenAccept(x -> {
                    // maybe we can parallelize this operation in order to make it fast
//                        Arrays.stream(oldBuckets).forEach(map -> map.forEach(this::put));
                    Set<Member> members = ring.getMembers();
                    LOGGER.info("Joined a cluster which has {} members {}.", members.size(), members);
                }
        ).join();

    }

    private synchronized CompletableFuture<Void> setRing(ConsistentHashRing newRing) {
        ConcurrentHashMap<K, V>[] newMap = createEmptyMap(newRing);
        int[] newBucketIds = createBucketForRing(newRing);

        ArrayList<CompletableFuture> migrations = new ArrayList<>();
        for (Entry<TokenRange, List<Member>> entry : newRing.getBuckets().entrySet()) {
            TokenRange range = entry.getKey();
            List<Member> members = entry.getValue();

            if (members.contains(localMember)) {
                List<Member> bucketMembers = entry.getValue();
                Member ownerMember = bucketMembers.get(members.indexOf(localMember) % bucketMembers.size());

                LOGGER.debug("asking entries [{}, {}] from {}", range.start, range.end, ownerMember);

                CompletableFuture<Map<K, V>> ask = serviceContext.ask(ownerMember, new ChangeRingRequest(range.start, range.end));
                CompletableFuture<Void> f = ask
                        .thenAccept(data -> {
                            newMap[Arrays.binarySearch(newBucketIds, range.id)].putAll(data);
                            if (!ownerMember.equals(localMember))
                                LOGGER.debug("{} elements in token[{} - {}] moved from {} to {}", data.size(), range.start, range.end, ownerMember, localMember);
                        });
                migrations.add(f);
            }
        }

        if (migrations.size() > 0) {
            migrationListeners.forEach(l -> Throwables.propagate(() -> l.migrationStart(localMember)));
        }

        // resume when all migrations completed
        return CompletableFuture.allOf(migrations.toArray(new CompletableFuture[migrations.size()]))
                .thenRun(() -> {
                    LOGGER.debug("{} migration completed.  New ring has {} buckets in member {}",
                            migrations.size(), newRing.getBuckets().size(), localMember);
                    synchronized (serviceContext) {
                        bucketIds = newBucketIds;
                        map = newMap;
                        ring = newRing;
                    }
                    migrationListeners.forEach(l -> Throwables.propagate(() -> l.migrationEnd(localMember)));
                    logOwnedBuckets();
                });
    }

    private CompletableFuture<Void> changeRing(ConsistentHashRing newRing) {
        ConsistentHashRing oldRing = ring;
        ConcurrentHashMap<K, V>[] newMap = createEmptyMap(newRing);
        int[] newBucketIds = createBucketForRing(newRing);

        ArrayList<CompletableFuture> migrations = new ArrayList<>();
        for (Entry<TokenRange, List<Member>> entry : newRing.getBuckets().entrySet()) {
            TokenRange range = entry.getKey();
            List<Member> members = entry.getValue();

            int start = oldRing.findBucketIdFromToken(range.start);
            int end = oldRing.findBucketIdFromToken(range.end - 1);
            if (end - start < 0) end = end + oldRing.getBucketCount();

            if (members.contains(localMember)) {
                long cursor = range.start;

                for (int bucketId = start; bucketId <= end; bucketId++) {
                    bucketId %= oldRing.getBucketCount();

                    ConsistentHashRing.Bucket oldBucket = oldRing.getBucket(bucketId);
                    List<Member> oldBucketMembers = oldBucket.members;

                    Member ownerMember;
                    if (oldBucketMembers.contains(localMember)) {
                        ownerMember = localMember;
                    } else {
                        int index = members.indexOf(localMember) % oldBucketMembers.size();
                        ownerMember = oldBucketMembers.get(index);
                    }

                    long queryStartToken = cursor;

                    long nextBucketToken = oldRing.getBucket(bucketId + 1).token;
                    long queryEndToken = (range.end - cursor > nextBucketToken - cursor) ? nextBucketToken - 1 : range.end - 1;
                    cursor = queryEndToken;

                    boolean isLocalOp = ownerMember.equals(localMember);
                    if (!isLocalOp)
                        LOGGER.debug("asking entries [{}, {}] from {}", queryStartToken, ownerMember);
                    else
                        LOGGER.trace("asking entries [{}, {}] from {}", queryStartToken, ownerMember);

                    CompletableFuture<Map<K, V>> ask = serviceContext.ask(ownerMember, new ChangeRingRequest(queryStartToken, queryEndToken, oldRing));
                    CompletableFuture<Void> f = ask
                            .thenAccept(data -> {
                                int startBucket = newRing.findBucketIdFromToken(queryStartToken);
                                int nextBucket = newRing.findBucketIdFromToken(queryEndToken);
                                if (startBucket == nextBucket) {
                                    newMap[Arrays.binarySearch(newBucketIds, startBucket)].putAll(data);
                                } else {
                                    data.forEach((key, value) -> {
                                        int i = Arrays.binarySearch(newBucketIds, newRing.findBucketIdFromToken(hash(key)));
                                        if (i >= 0) {
                                            Map<K, V> partition = newMap[i];
                                            partition.put(key, value);
                                        }
                                    });
                                }

                                if (!ownerMember.equals(localMember))
                                    LOGGER.debug("{} elements in token[{} - {}] moved from {} to {}", data.size(), queryStartToken, queryEndToken, ownerMember, localMember);
                            });
                    migrations.add(f);
                }

            } else {
                for (int bucketId = start; bucketId <= end; bucketId++) {
                    // we don't remove the old entries because
                    // the new member will request the entries and remove them via migration request,
                    // so it allows us to avoid the requirement for consensus between nodes when changing ring.
                    dataWaitingForMigration.put(range, getPartition(bucketId));
                }
            }
        }

        if (migrations.size() > 0) {
            migrationListeners.forEach(l -> Throwables.propagate(() -> l.migrationStart(localMember)));
        }

        // resume when all migrations completed
        return CompletableFuture.allOf(migrations.toArray(new CompletableFuture[migrations.size()]))
                .thenRun(() -> {
                    LOGGER.debug("{} migration completed.  New ring has {} buckets in member {}",
                            migrations.size(), newRing.getBuckets().size(), localMember);
                    synchronized (serviceContext) {
                        bucketIds = newBucketIds;
                        map = newMap;
                        ring = newRing;
                    }
                    migrationListeners.forEach(l -> Throwables.propagate(() -> l.migrationEnd(localMember)));
                    logOwnedBuckets();
                });
    }

    Map<K, V> getPartition(int bucketId) {
        int i = Arrays.binarySearch(bucketIds, bucketId);
        return i >= 0 ? map[i] : null;
    }

    @Override
    public synchronized void memberRemoved(Member member) {
        if (isPaused()) {
            addQueueIfPaused(() -> memberRemoved(member));
        } else {
            changeRing(ring.removeNode(member));
        }
    }

    @Override
    public void clusterMerged(Set<Member> newMembers) {

    }

    @Override
    public void onClose() {
        Arrays.stream(map).forEach(x -> x.clear());
    }

    public CompletableFuture<Void> putAll(Map<K, V> fromMap) {
        Map<Member, List<Entry>> m = new HashMap<>();

        for (Entry entry : fromMap.entrySet()) {
            for (Member member : ring.findBucketFromToken(hash(entry.getKey())).members) {
                m.getOrDefault(member, new ArrayList()).add(entry);
            }
        }

        m.forEach((key, value) -> serviceContext.send(key, new PutAllRequest(value)));

        CompletableFuture[] completableFutures = fromMap.entrySet().stream()
                .map(entry -> put(entry.getKey(), entry.getValue()))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(completableFutures);
    }

    public CompletableFuture<Void> put(K key, V val) {
        int bucketId = ring.findBucketIdFromToken(hash(key));
        ConsistentHashRing.Bucket bucket = ring.getBucket(bucketId);

        CompletableFuture<Void>[] stages = new CompletableFuture[bucket.members.size()];
        int idx = 0;
        for (Member next : bucket.members) {
            if (next.equals(localMember)) {
                putLocal(key, val);
                stages[idx++] = CompletableFuture.completedFuture(null);
            } else {
                stages[idx++] = serviceContext.ask(next, new PutMapOperation(key, val));
                ;
            }
        }

        // we should use eventual consistency here,
        // that said it should wait quorum of servers to complete this Future.
        return CompletableFuture.allOf(stages);
    }

    public CompletableFuture<V> get(String key) {
        int bucketId = ring.findBucketId(key);
        ConsistentHashRing.Bucket bucket = ring.getBucket(bucketId);

        ArrayList<Member> members = bucket.members;
        if (members.contains(localMember)) {
            return CompletableFuture.completedFuture(getPartition(bucketId).get(key));
        }

        return serviceContext.ask(members.get(random.nextInt(members.size())),
                new GetRequest(this, key));
    }

    public CompletableFuture<V> syncAndGet(String key) {
        int bucketId = ring.findBucketId(key);
        ConsistentHashRing.Bucket bucket = ring.getBucket(bucketId);

        AtomicReference<V> merged = new AtomicReference<>();
        CompletableFuture<Void>[] res = new CompletableFuture[bucket.members.size()];

        for (int i = 0; i < bucket.members.size(); i++) {
            CompletableFuture<V> ask = serviceContext.ask(bucket.members.get(i), new GetRequest(this, key));
            res[i] = ask.thenAccept(x -> {
                V v = merged.get();
                if (v == null) {
                    merged.set(x);
                } else {
                    merged.set(mergePolicy.merge(v, x));
                }

            });
        }

        return CompletableFuture.allOf(res).thenApply(x -> merged.get());
    }

    public int getLocalSize() {
        return Arrays.stream(map).collect(Collectors.summingInt(value -> value.size()));
    }

    public CompletableFuture<Map<Member, Integer>> size() {
        Request<RingMap, Integer> req = (service, serviceContext) -> serviceContext.reply(service.getLocalSize());
        Map<Member, CompletableFuture<Integer>> resultMap = serviceContext.askAllMembers(req);

        Map<Member, Integer> m = new ConcurrentHashMap<>(serviceContext.getCluster().getMembers().size());
        m.put(localMember, getLocalSize());
        CompletableFuture<Map<Member, Integer>> future = new CompletableFuture<>();

        resultMap.forEach((key, f) -> f.thenAccept(x -> {
            m.put(key, x);
            resultMap.remove(key);
            if (resultMap.size() == 0) {
                future.complete(m);
            }
        }));

        return future;
    }

    public ConsistentHashRing getRing() {
        return ring;
    }

    public int getReplicationFactor() {
        return replicationFactor;
    }

    public void listenMigrations(MigrationListener migrationListener) {
        migrationListeners.add(migrationListener);
    }

    public static class PutMapOperation implements Request<RingMap, Void> {
        Object key;
        Object value;

        public PutMapOperation(Object key, Object value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public void run(RingMap service, OperationContext serviceContext) {
            service.putLocal(key, value);
        }
    }

    void putLocal(K key, V value) {
        Map<K, V> partition = getPartition(ring.findBucketIdFromToken(hash(key)));
        if (partition == null) {
            LOGGER.error("Discarded put request for key {} because node doesn't own that token.", key);
        } else {
            partition.put(key, value);
        }
    }

}