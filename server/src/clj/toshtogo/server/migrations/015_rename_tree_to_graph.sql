alter table job_dependencies rename column link_tree_id to dependency_graph_id;
---
alter table job_trees rename column tree_id to graph_id;
---
alter table job_trees rename to job_graphs;
---
alter table jobs rename column home_tree_id to home_graph_id;
