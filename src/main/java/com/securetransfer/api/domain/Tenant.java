package com.securetransfer.api.domain;

/**
 * Which "bank" a row belongs to. The app runs as ONE deployment against ONE
 * database, but every tenant-scoped row (users, customers, accounts, transfers,
 * fraud reviews) carries a tenant so the two worlds never see each other:
 *
 * <ul>
 *   <li>{@code STAFF} — the real showcase bank shared by the {@code admin} and
 *       {@code teller} logins.</li>
 *   <li>{@code DEMO}  — the public demo login ({@code demo}) gets its own
 *       self-contained sandbox: it only ever sees/acts on DEMO data, and its
 *       activity never touches the STAFF bank.</li>
 * </ul>
 *
 * A user's tenant is fixed at login and carried in the JWT; it is stamped onto
 * every row that user creates and checked on every read/action. Crossing the
 * boundary looks like "not found" (404), so one bank can't even probe the
 * other's ids.
 */
public enum Tenant {
    STAFF,
    DEMO
}
