# Databricks Lakehouse Migration Guide

## Upgrading to 3.0.0

This version adds an `_airbyte_generation_id` column to the raw and final tables and a `sync_id` entry in `_airbyte_meta`.
For now, these values will always be set to 0 - they will be used by the upcoming refreshes feature.

There is no automated upgrade process. After selecting `Upgrade`, you should:
1. `DROP` any raw/final tables created using a 2.x sync
1. Clear (reset) your connection to resync all your data

## Upgrading to 2.0.0

More to come. this is a preview release.

This version introduces [Destinations V2](/release_notes/upgrading_to_destinations_v2/#what-is-destinations-v2), which provides better error handling, incremental delivery of data for large syncs, and improved final table structures.
To review the breaking changes, and how to upgrade, see [here](/release_notes/upgrading_to_destinations_v2/#quick-start-to-upgrading). These changes will likely require updates to downstream dbt / SQL models, which we walk through [here](/release_notes/upgrading_to_destinations_v2/#updating-downstream-transformations).
Selecting `Upgrade` will upgrade **all** connections using this destination at their next sync. You can manually sync existing connections prior to the next scheduled sync to start the upgrade early.

Worthy of specific mention, this version includes:

- Per-record error handling
- Clearer table structure
- Removal of sub-tables for nested properties
- Removal of SCD tables

Learn more about what's new in Destinations V2 [here](/platform/using-airbyte/core-concepts/typing-deduping).
