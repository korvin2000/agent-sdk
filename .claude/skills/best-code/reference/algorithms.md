# Algorithms, models, and performance

## Name the problem before choosing an implementation

Most "hard" problems are a named problem with a known solution and a known complexity. Before
inventing anything:

1. **Identify the problem class.** Shortest path, matching, scheduling, ranking, dedup, interval
   overlap, top-k, constraint satisfaction, consensus.
2. **Name it in the plan with its complexity and the expected N**: "A* with a Manhattan heuristic
   over the nav grid, O(E log V), grid ≤ 4096 cells". O(n²) over 20 items is correct engineering;
   over 20 000 it is an outage. Say which case you are in.
3. **Prefer the standard library, then a well-maintained dependency, then your own.** Writing your
   own crypto, RNG, date arithmetic, JSON parsing, or collision detection needs an explicit reason.
4. **Check the assumptions the known solution requires** — sorted input, non-negative weights,
   metric heuristic, bounded key space, immutability during iteration. A correct algorithm applied
   outside its preconditions is a silent wrong answer.
5. **When you do write something specialized, keep a simple reference implementation or a
   reproducible benchmark next to it** as the oracle. Otherwise nobody can ever change it.

Mathematical models are design tools, not decoration: ask whether the problem is a known
optimization, probability, graph, or queueing problem. The closed form is usually simpler than the
heuristic and always easier to test.

## Canon worth checking before improvising

| Problem shape | Canon |
|---|---|
| Pathfinding, navigation | A*, JPS, flow fields, navmesh, hierarchical A* |
| Spatial queries, collision | uniform grid, quadtree/octree, BVH, sweep-and-prune, spatial hashing |
| Matchmaking, rating | Elo, Glicko-2, TrueSkill; bucketed queues; bipartite matching; Hungarian |
| Scheduling, ticks | priority queue, timer wheel, fixed-timestep accumulator |
| State replication | delta compression, snapshot interpolation, dead reckoning, area-of-interest filtering, bit packing |
| Rollback, prediction | ring buffer of snapshots, input prediction + reconciliation, deterministic re-simulation |
| Economy, balance | linear programming, Markov chains, Monte Carlo, expected-value analysis |
| Procedural generation | Perlin/simplex/worley noise, wave function collapse, Poisson-disc sampling, L-systems, seeded PRNG (xorshift, PCG) |
| Progression, unlocks | DAG + topological sort; rule tables |
| Anomaly detection, anti-cheat | server authority + statistical outliers; sequence validation |
| Concurrency, ordering | vector/Lamport clocks, idempotency keys, monotonic sequence numbers |
| Merging concurrent edits | operational transform, CRDT — only where genuinely needed |
| Load and back-pressure | token bucket, leaky bucket, bounded queue with a shed policy, circuit breaker |
| Search and ranking | inverted index, trie, BK-tree, top-k with a heap |

## Verification oracles for algorithmic code

Algorithmic code is where property tests pay best, because the properties are already known:

| Kind | Oracle |
|---|---|
| Optimization / search | a brute-force reference on small inputs must agree exactly |
| Encoding, serialization | round-trip identity |
| Sorting, ranking | output is a permutation of input and is ordered by the stated key |
| Pathfinding | the returned path is connected, legal, and no shorter path exists in a small grid |
| Simulation step | same state + same inputs + same seed → bit-identical result |
| Economy, resources | conservation: nothing is created or destroyed except by a named operation |
| Concurrent processing | the result equals some valid sequential ordering |
| Numeric | a stated error bound, not exact equality |

## Performance discipline

- **Complexity up front; optimization only against a measurement.** Big-O is design;
  micro-optimization is measurement.
- **Express requirements as budgets with tests:** server tick ≤ 16 ms at 100 players · p99 API
  latency ≤ 120 ms · client bundle ≤ 250 kB gzipped · ≤ 1 query per request in the hot path. A
  budget without a test is folklore.
- **Fix the algorithmic and I/O problems first** — N+1 queries, unbounded result sets, per-frame
  allocation, repeated deserialization, chatty network calls. These dominate; clever inner loops
  rarely do.
- **Do not let performance speculation distort structure.** Premature optimization is a complexity
  purchase against an unproven need.
- **Measure in the shape of production:** realistic data volume, realistic concurrency, realistic
  latency. A benchmark at N=10 predicts nothing about N=100 000.
- **Never claim a performance improvement you did not measure.**
