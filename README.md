# OpenFlowSDN

## How far I got

- My program Compiles

- I was able to get pingall working on a single,3 topology

- After many excruciating hours, I was able to get my program working for all of the tested static topologies except the mesh,5 topology. The mesh five topology gives a null pointer error on a floodlight module operating outside of my code. I have been trying to figure out why but haven't located the error as it is referenced in a separate module.

- I tested link s1 s2 down on the `someloops` topology with success. I also brought a host offline on the same topology and received expected behavior for dynamic configurations. 


## Changes I made

- At the beginning of the file, I built four helper methods `buildRules`, `destroyRules`, `buildAllRules`, `destroyAllRules` to make my life easier these methods essentially erase and install rules by iterating through the relevant switches and hosts, they have comments explaining what they do. 

- I also wrote a shortest path method I call `Dijkstra`, which calls `shortestPath`, which calls `BFS` BFS is a Breadth-First Search algorithm, and shortest path calculates the shortest path from the pred and distance hashmaps populated in BFS. All of my methods of finding the shortest path are based off of this resource [here](https://www.geeksforgeeks.org/shortest-path-unweighted-graph/)

- I then call the corresponding helper methods in each of the TODO sections. 
