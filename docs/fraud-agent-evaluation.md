# Evaluating the Fraud-Triage Agent

*A live red-team (adversarial stress-test) of SecureTransfer's Phase-6 AI fraud agent — how it was tested, what it got right, and where it fails.*

Run date: 2026-07-15 · Target: the deployed backend on Render · Model under test: `claude-haiku-4-5-20251001`

---

## TL;DR

I drove **13 real transfers** through the live API to trigger **10 real fraud reviews** by the AI agent, spanning a deliberate range of risk — from a benign internal move to account-draining, high-velocity bursts, and three planted money-laundering patterns (a probe-then-drain, structuring, and a round-trip). I then ran a **22-agent adversarial evaluation** — a panel of separate AI judges — that audited each verdict for two things: *coherence* (does the reasoning match the facts?) and *calibration* (is the score and action right?). Because those judges are themselves LLM agents, this is rigorous *automated* cross-checking, not external human sign-off (see the limitation note under *How I tested it*).

**The verdict on the agent:** solid directional instincts and a working human-in-the-loop safety net, but three real weaknesses that a bank would want fixed:

1. **It misses structuring entirely** — repeated just-under-$10,000 transfers (a classic laundering evasion) scored the *lowest* of the whole run.
2. **It hallucinates "insufficient funds / overdraft"** on transfers that provably had enough money — a data-plumbing bug, not a reasoning one.
3. **Its numeric scores aren't stable** enough to drive automatic actions — identical inputs can land two action tiers apart.

Crucially, **the safety design held**: every flagged transfer went to a human review queue, and nothing the agent said moved money on its own.

---

## Background: what is actually being tested

SecureTransfer's fraud system has two layers:

1. **Deterministic rules** (`FraudRuleEvaluator`) that *flag* a transfer. There are three:
   - `LARGE_AMOUNT` — the transfer is at or above **$10,000**.
   - `HIGH_VELOCITY` — the sender already made **3 or more** sends in the last **60 minutes**.
   - `NEW_PAYEE` — this is the **first** transfer on this specific sender → receiver pair.
2. **The AI agent** (Phase 6). When a transfer is flagged, an asynchronous agent uses three **read-only** tools (look up an account, list an account's recent transfers — both incoming and outgoing, and compute velocity stats) to investigate, then returns a **risk score (0–100)** and a **recommended action** — `APPROVE`, `HOLD`, or `ESCALATE`. It only *recommends*; a human teller makes the final call, and every decision is logged.

A key design fact that matters below: **money moves even when a transfer is flagged.** Flagging is advisory. The transfer debits and credits normally, the review happens afterward (`AFTER_COMMIT` — Spring only runs it once the transfer has durably committed to the database), and a human decides what to do. That is by design — but it has a side effect the agent trips over (see Finding #2).

---

## How I tested it

### Scenario design

I created 6 customers and 9 USD accounts, then choreographed 13 transfers so that each one isolates a specific behavior. The ordering is deliberate — `HIGH_VELOCITY` only fires after a sender has 3 prior sends, so the sequence has to be run top to bottom, once, against shared state. That rules out a naive parallel test; it had to be one sequential script.

Three of the scenarios plant real laundering **patterns** the rules can't catch on their own — the whole point is to see whether the *agent* notices:

| # | Transfer | Rule flags | What it probes |
|---|----------|-----------|----------------|
| 1 | Internal $25,000 (A→B, same customer) | LARGE, NEW_PAYEE | Baseline: should score low |
| 2 | Repeat $100 (A→B) | *none* | Control — should not flag at all |
| 3 | Cross-customer $25,000 (A→C) | LARGE, NEW_PAYEE | External large transfer amid a burst |
| 4 | Velocity $12,000 (A→D) | LARGE, HIGH_VELOCITY, NEW_PAYEE | The highest-rule-count case |
| 5 | Drain $49,000 (E→D, 98% of balance) | LARGE, NEW_PAYEE | Account draining |
| 6 | **$1 probe** (F→G) | NEW_PAYEE | A "test transaction" before a drain |
| 7 | **$60,000 drain** (F→G, same payee) | LARGE | Probe-then-drain — does it connect the $1? |
| 8a | $9,500 (C→D) | NEW_PAYEE | First of a **structuring** series |
| 8b | $9,500 (C→D) | *none* | Control |
| 8c | $9,400 (C→D) | *none* | Control |
| 8d | $9,300 (C→D) | HIGH_VELOCITY | **Structuring**: four sends just under $10k |
| 9 | Round-trip $20,000 (B→A) | LARGE, NEW_PAYEE | Money bounced back to its source |
| 10 | Replay $25,000 (H→I, fresh accounts) | LARGE, NEW_PAYEE | Consistency: same facts as #3 |

### The two-stage harness

- **Stage 1 — collection.** A single sequential script fired the 13 transfers and polled the fraud-review queue until every flagged transfer reached a completed verdict, capturing each one's score, action, model, and full reasoning.
- **Stage 2 — adversarial evaluation.** A 22-agent panel: for each of the 10 verdicts, two separate judges (a *coherence* lens and a *calibration* lens), plus one *cross-scenario consistency* judge and a final synthesis. The judges were given the scenario facts and were free to read the repository source to check the agent's claims — which they did, citing exact files and line numbers.

> **Limitation to be honest about:** the judges are LLM agents from the same model family as the agent under test, so they can in principle share its blind spots. This is strong automated cross-checking, not independent human validation — read the findings with that caveat.

---

## Results

Every flagged transfer produced a real verdict from `claude-haiku-4-5-20251001` (no rules-fallback), and all three controls (#2, #8b, #8c) correctly produced **no** review — the rules don't over-flag.

### The risk gradient

The agent produced a genuine spread, not a flat "everything looks fine":

```
25 ── 25 ── 35 ── 65 ── 72 ── 72 ── 75 ── 82 ── 85 ── 92
#8a   #8d   #1    #6    #7    #10   #9    #3    #4    #5
APPRV APPRV APPRV HOLD  ESCL  HOLD  ESCL  ESCL  ESCL  ESCL
```

At the extremes this is sensible: an account-draining transfer (#5, 92) outranks a velocity burst (#4, 85) outranks a plain cross-customer large transfer (#3, 82), and benign internal moves sit near the bottom. The problems are in the middle and — most importantly — in *which* scenarios landed at the bottom.

### The scorecard

Each verdict audited on two lenses. ✅ = holds up, ⚠️ = minor concern, ❌ = major concern.

| # | Scenario | Score / Action | Coherence | Calibration | One-line assessment |
|---|----------|:---:|:---:|:---:|---|
| 1 | Internal $25k | 35 / APPROVE | ❌ | ✅ | Right call, but reasoning **invented a "prior transaction"** that contradicts its own NEW_PAYEE flag |
| 3 | Cross-cust $25k | 82 / ESCALATE | ✅ | ✅ | **Clean** — the math checks out, caught the burst |
| 4 | Velocity $12k | 85 / ESCALATE | ✅ | ✅ | **Clean** — best-reasoned verdict of the run |
| 5 | Drain $49k | 92 / ESCALATE | ❌ | ✅ | Right score, **hallucinated an overdraft** — "insufficient balance ($1,000)" |
| 6 | $1 probe | 65 / HOLD | ⚠️ | ⚠️ | **Connected it to the $60k drain** that followed |
| 7 | $60k drain | 72 / ESCALATE | ❌ | ⚠️ | Caught the probe pattern, but **invented an "overdraft"** as its main basis |
| 8a | $9.5k structuring | 25 / APPROVE | ❌ | ❌ | **Missed structuring**; invented "4 transfers" that never happened |
| 8d | $9.3k structuring | 25 / APPROVE | ⚠️ | ❌ | **Missed structuring** — waved it off as "legitimate intra-customer fund movement" |
| 9 | Round-trip $20k | 75 / ESCALATE | ❌ | ❌ | Escalated for a **fake overdraft**, never saw the circular flow |
| 10 | Replay of #3 | 72 / HOLD | ✅ | ✅ | Clean — but the **action flipped** vs #3 (ESCALATE → HOLD) |

---

## Findings

### 🔴 Finding #1 — Structuring is a blind spot

The two structuring scenarios (#8a and #8d) break a large sum into transfers deliberately kept just under the system's **$10,000 large-amount threshold** (9,500 / 9,500 / 9,400 / 9,300). Sizing transactions to stay under a reporting-style line like this is **structuring** — a federal crime (31 U.S.C. §5324) and a primary trigger for a Suspicious Activity Report, *regardless* of whether the underlying money is clean. (The informal term *smurfing* usually implies several people making the sub-threshold deposits; here it's a single actor moving between their own accounts, so *structuring* is the precise word. And the real Bank Secrecy Act reporting threshold is for *cash* transactions — here $10k is simply the amount this system treats as "large," used as a reporting-style proxy.)

The agent scored both **25 / APPROVE — the lowest scores in the entire run**, below a harmless $1 transfer. Its reasoning for #8d saw the exact tell ("consistent amounts") and rationalized it away as *"legitimate intra-customer fund movement."* The mitigation it leaned on — same-customer transfers with "no indication of account compromise" — is not a defense. Routing money through accounts you control is itself **layering** (the money-laundering stage that shuffles funds around to obscure their trail), so a same-customer pattern here is a red flag, not a reassurance.

**Why it happens:** the agent over-weights "same-customer" as a blanket risk suppressor, and nothing in its toolset draws its attention to threshold proximity.

**Fix:** add an explicit structuring signal — count a sender's sub-threshold transfers within the velocity window and surface "N transfers within $X of the reporting threshold" to the agent (or as a fourth rule). This is the single biggest capability gap.

### 🟠 Finding #2 — Phantom overdrafts (a data-plumbing bug)

On scenarios #5, #7, and #9, the agent justified a high score by claiming the sender had **"insufficient balance"** or an **"overdraft."** The verification judges proved this is *structurally impossible*: `TransferService` rejects any transfer where `balance < amount` **before** the transfer is ever saved or flagged. A transfer that reaches fraud review has, by definition, already cleared the funds check.

What's actually happening: because money moves even when flagged, by the time the agent calls its "look up account" tool the sender's balance is the **post-debit** balance (e.g. account E drained from $50,000 to $1,000 in #5). The agent reads that $1,000 leftover and misreads it as "insufficient funds for a $49,000 transfer."

So the agent lands the right *action* for the wrong *reason* — its scores on these cases ride a phantom signal rather than real fraud evidence.

**Fix:** hand the agent a **pre-transfer balance snapshot** (or clearly label the field as "current balance, after this transfer"). This is a small, contained change to how the agent's account tool is populated, and it removes the fabricated reasoning in one move.

### 🟠 Finding #3 — Scores aren't stable enough to auto-action

- **Replay #3 → #10:** the same *engineered-identical* facts ($25k to a brand-new external payee) landed at 82/ESCALATE and 72/HOLD — the action tier flipped. Part of the 10-point gap is fair (account A had a visible burst of history by #3; #10's accounts were fresh), but the *action* still shouldn't flip on that.
- **Two 72s, two actions:** #7 and #10 both scored **72**, yet #7 was ESCALATE and #10 was HOLD — the same number mapped to different actions in a single run.
- **#1 vs #9:** two internal transfers with *identical* flags (LARGE + NEW_PAYEE) scored 35/APPROVE and 75/ESCALATE — a 40-point, two-tier gap, with the *larger* transfer scoring *lower* (though these two differ in nature — a one-way move vs a round-trip).

The takeaway: the numeric band and the score → action mapping wobble enough that you would **not** want to wire the score directly to an automatic decision. Worth knowing: today the AI agent's action comes straight from the model's own output, not from score bands in code — only the deterministic rules-fallback maps score → action, at fixed 70/40 cutoffs.

**Fix:** define fixed score bands for APPROVE / HOLD / ESCALATE with a small tiebreak zone near the escalation threshold, so borderline cases don't flip actions run-to-run.

---

## What worked well

- **The rules don't over-flag.** All three control transfers (a repeat $100, and two sub-$10k sends to a known payee) correctly produced no review.
- **The agent adds value over the raw rules.** On #1 it correctly *overrode* the mechanical rule-sum (which would have said HOLD) down to APPROVE by recognizing a same-customer internal move — the kind of judgment the AI layer is meant to add. (Honest caveat: it reached the right *call* through *incoherent* reasoning — it invented a prior relationship with the payee that its own NEW_PAYEE flag contradicts, per the scorecard. Credit the outcome here, not the rationale — the same right-answer/wrong-reason pattern as Finding #2.)
- **It caught the probe-then-drain.** Reviewing the harmless $1 transfer (#6), the agent used its history tool, saw the $60,000 that followed to the same new payee, and flagged it as "account verification fraud." That is real multi-transaction reasoning.
- **The extremes are ordered correctly** (drain > velocity > cross-customer large > … > benign internal).
- **The safety guarantees held.** Nothing auto-decided money movement; every flagged item waited for a human.

---

## What this exercise demonstrates

Beyond the agent itself, this was a test of the *system's* safety posture, and it passed the parts that matter for a banking context: read-only agent tools, advisory-only recommendations, a human-in-the-loop queue, and an append-only audit trail. The weaknesses found are about *detection quality*, not about the agent being able to do something it shouldn't.

Two of the three findings are small, concrete code changes (a pre-transfer balance field; a structuring signal) and are tracked as follow-up work.

---

## Reproducing this

The evaluation was fully scripted, and the run captured two working artifacts (kept outside the repo): the raw transfer + verdict records (every flagged transfer's score, action, model, and reasoning) and all 20 coherence/calibration audits verbatim plus the cross-scenario consistency analysis.

To re-run: authenticate as an admin, create the 6 customers / 9 accounts, fire the 13 transfers in order (each with a unique `Idempotency-Key`), then poll `GET /fraud-reviews` until every flagged transfer reaches `AGENT_COMPLETED`. The scenario table above is the full recipe.

> **Notes.**
> - This run wrote demo data (6 customers, 9 accounts, 13 transfers, 10 reviews) to the live database. Reset it before recording a clean demo.
> - If you dump the review queue directly, you may see a couple of extra older reviews from earlier smoke tests; the **10** reviews above are only those produced by this run's 13 transfers.
