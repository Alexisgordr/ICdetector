# ICdetection cheat sheet: the rules on one page

A quick reference to what each rule watches, how many points it takes and when it abstains.
Values match the 3.0 code. For the full explanation, see [MANUAL.md](MANUAL.md).

## The idea in three sentences

An app cannot see an attack, only its effects. Each rule watches something an IMSI catcher has to
do to succeed: pull the phone in, take on an identity, push it down to a weaker network, or keep it
attached. Several rules look at the same step from different angles, so when one goes blind
another one keeps watching.

## How an alarm is reached

1. **Every cycle starts at 100 points.** Each rule that fails subtracts its points.
2. **A cycle is suspicious below 70.** That takes at least 31 points, and almost no rule takes
   that many on its own: it usually needs **at least two rules** failing together. The exception is
   H11 at its most severe (up to −65).
3. **It has to repeat for 3 consecutive cycles** before an alarm is confirmed. A single spike is
   not enough. The streak restarts after a loss of service or a gap of more than 2 minutes.
4. **A rule that cannot evaluate says `N/A` and names the reason**, for example `H11(fix_viejo)`.
   `N/A` does not mean "all clear": it means "I could not check". The reasons are stored in
   `NotEvaluatedHeuristics`.
5. **Points add up as they are; the Bayesian probability does not.** Alongside the score, the app
   computes a threat probability (prior 0.5 %, capped at 95 %) in which correlated rules are grouped
   and only the strongest of each group counts:
   - **RF dominance:** H1, H2, H6, H7, H13, H14
   - **Mobility:** H10, H11, H15, H16
   - **Weak identity:** H4, H5
   - **Independent:** H3
6. **Damping applies to the probability, not to the points.** H1, H2 and H7 weigh less where few
   neighbours are visible, and H1, H2, H6 and H7 weigh less on cells with a long clean history.
   No other rule is ever damped.

> **In one sentence:** two different symptoms at the same time, for three cycles in a row.

## The 17 rules, by attack step

### 1. Pull in: the catcher has to "shout louder" than the real towers

| Rule | What it watches | Points | When it abstains (`N/A`) |
|---|---|---|---|
| **H1** Isolated cell | No neighbours at all and serving signal ≥ −80 dBm, confirmed over 3 deliveries | −15 | Wi-Fi on, CSG cell, or still confirming |
| **H2** Power jump | Serving ≥ −75 dBm and more than 35 dB above the strongest neighbour of the same technology | −20 | No same-technology neighbour, Wi-Fi on, or CSG cell |
| **H6** Timing Advance | TA puts the tower 100 m away or closer, with signal ≥ −60 dBm | −15 | No TA or unknown TA unit (never on NR), or CSG cell |
| **H13** Signal baseline | The cell is much stronger than its own history at this spot: ≥ 18 dB and ≥ 3σ above the mean (and above its P99 with ≥ 20 samples), with signal ≥ −95 dBm | −12 / −18 / −25 | Wi-Fi on, cell not yet trusted (5 clean observations on 2 days), or fewer than 5 samples within 500 m |
| **H13** RSRQ/SINR branch | RSRQ and SINR both far from the cell's fingerprint. Only counts if the power branch did not fail | −15 | Fewer than 30 samples within 1 km in 90 days |

### 2. Identity: the catcher has to pose as an operator

| Rule | What it watches | Points | When it abstains (`N/A`) |
|---|---|---|---|
| **H3** MCC vs SIM | The serving network claims a different country from the SIM, with no roaming and no neighbour sharing that country | −30 | Roaming, border (a neighbour shares the MCC), no SIM, or no service state |
| **H4** MNC count | More than 4 different operators (MNC) around | −15 | No MNC on the serving cell or the neighbours (LTE/NR neighbours do not report it, so in practice it works on 2G/3G) |
| **H5** TAC deviation | The serving TAC matches none of the same-operator, same-technology neighbours | −20 | No such neighbours with a TAC (common on LTE/NR, where neighbours only expose PCI, EARFCN and power), or CSG cell |
| **H15** RF identity | The same Cell ID keeps alternating between two PCIs on the same carrier, recently and repeatedly | −30 | Fewer than 4 observations of the cell |

### 3. Push down: the catcher wants a weaker network

| Rule | What it watches | Points | When it abstains (`N/A`) |
|---|---|---|---|
| **H14** Band downgrade | Moved from a high band to a sub-GHz band (LTE→LTE or NR→NR) while the old signal was ≥ −90, the new one is ≥ −95 and not weaker, and the signal was not fading | −25 | No previous band, a change between LTE and NR, or the same base station (LTE) |
| **2G/3G notice** | Warns that the phone is on a legacy network | No points | Modes: OFF, NOTIFY (default), NOTIFY_SOUND. Always logged |

### 4. Keep the phone: moving, bouncing or appearing where it should not

| Rule | What it watches | Points | When it abstains (`N/A`) |
|---|---|---|---|
| **H7** Ghost neighbours | Serving ≥ −65 dBm while every same-technology neighbour looks dead (≤ −110) | −25 | No same-technology neighbour, Wi-Fi on, or CSG cell |
| **H10** Ping-pong | 3 or more cell changes within 10 s while not moving fast (< 8 m/s) | −25 | No GPS speed |
| **H11** Geographic | You are far from where this cell was seen before (≥ 2 records and ≥ 30 % of them); more points if PCI or channel also differ | −10 to −65 | No GPS fix ≤ 60 s (`H11(fix_viejo)`), accuracy unknown or worse than 100 m (`H11(fix_impreciso)`), or no located history |
| **H16** Transition coherence | One handover is physically impossible: you barely moved (≤ 1 km), but the two cells were learned ≥ 10 km apart and you are in the old cell's zone | −15 | Handover longer than 120 s, missing fix ≤ 10 s at either end, accuracy worse than 75 m, or fewer than 4 learned positions per cell |

H16 shows each handover result for 20 s, then returns to `N/A` until the next handover. It only gets
regular coverage with **Continuous** GPS mode.

### 5. Rules that never take points

| Rule | What it is for |
|---|---|
| **H8** Data quality | Flags a channel (ARFCN) outside the 3GPP tables: the modem is reporting badly, not an attack |
| **H9** Ciphering | Reserved. Always `N/A`, because Android does not expose the modem's ciphering state |
| **H12** Latency + RF | Retired in 3.1.0 with the latency probe (it needed an Internet connection). Always `N/A` |
| **H17** Cell churn | Shadow mode (3.1.0): distinct cells and changes over 15 min, with motion, stored in every row. Always `N/A` until calibrated |

> **Remember:** H2 (against the neighbours, now) and H13 (against the cell's own history) look at
> power from different references. H1 (no neighbours) and H7 (neighbours crushed) split the
> neighbour cases between them. H10 counts how often you change cell; H16 checks whether one change
> makes sense.

## Learning layers (no points)

- **Reputation:** a long clean history (trust score ≥ 60 or ≥ 80) damps H1, H2, H6 and H7 in the
  probability. It never damps H3, H11, H13, H14 or H15.
- **CSG exemption** (femtocells): H1, H2, H5, H6 and H7 are skipped only when that full cell
  identity already has local history (10 observations on 2 different days).
- **Baselines use a sliding window:** H13 power reads the 200 most recent clean rows of the cell
  (30 days) and keeps those within 500 m; the RSRQ/SINR fingerprint reads 200 (90 days) and keeps
  those within 1 km. Nothing is deleted: the window only limits what goes into the calculation.
- **Stable-Site:** learns which places are stable in your routine. **Shadow mode**: it records, but
  never changes the score.
- **Local profile:** compares each cell with its usual behaviour and marks deviations at 2, 3 and
  4σ. Also **shadow mode**, and its thresholds are **not calibrated yet**.

## What the app cannot see

- **Ciphering and signalling.** Android does not let an app read network messages. For example,
  the TAU/Attach rejects an LTE catcher uses happen before authentication and never reach the app.
- **A passive catcher** that only listens and does not transmit: nothing measurable changes.
- **An attack that copies a real cell well** (same PCI, EARFCN and similar power) and lasts only
  briefly: it may not complete the 3 cycles.
- **What the phone does not report.** Each manufacturer exposes different data; that is why `N/A`
  and H8 exist.
- **On 5G SA** the subscriber identity travels concealed (SUCI), so classic IMSI capture no
  longer works.

> **In one sentence:** ICdetection is a symptom detector, not a guarantee. If it does not fire, that
> does not mean nothing is there; it means it saw no symptoms.
