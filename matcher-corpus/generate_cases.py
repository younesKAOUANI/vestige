#!/usr/bin/env python3
"""Generates matcher-corpus/cases/*.json from hand-designed before/after scenarios.

Each scenario below is authored deliberately - the refactor, the rule, which findings should
survive it and which should not, is a decision made by a person reading real-looking code, not
generated data. What this script automates is turning that decision into a fixture without the
transcription errors that come from hand-counting line numbers or hand-copying snippet text: line
numbers and snippet text are both *computed* from the same source strings a reader can see below,
so a fixture can never claim a line number or a snippet the source does not actually contain.

Run: python3 matcher-corpus/generate_cases.py
Output: matcher-corpus/cases/NN-slug.json, one file per scenario, plus matcher-corpus/cases/README
is not written here - see matcher-corpus/README.md for the format description.
"""
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent
CASES_DIR = ROOT / "cases"

DEFAULT_PATH = "src/main/java/com/acme/PaymentService.java"
ORDER_SERVICE_PATH = "src/main/java/com/acme/OrderService.java"
PRICING_PATH = "src/main/java/com/acme/Pricing.java"
NOTIFICATION_PATH = "src/main/java/com/acme/NotificationService.java"
BIG_FILE_PATH = "src/main/java/com/acme/BigFile.java"
REPORT_GENERATOR_PATH = "src/main/java/com/acme/ReportGenerator.java"


def line_of(source: str, needle: str) -> int:
    for number, line in enumerate(source.splitlines(), start=1):
        if needle in line:
            return number
    raise SystemExit(f"needle not found: {needle!r} in:\n{source}")


def snippet_of(source: str, needle: str) -> str:
    for line in source.splitlines():
        if needle in line:
            return line
    raise SystemExit(f"needle not found: {needle!r}")


class Case:
    def __init__(self, case_id, description, refactor_shape):
        self.case_id = case_id
        self.description = description
        self.refactor_shape = refactor_shape
        self.before = []
        self.after = []
        self.expected = []
        self.renames = {}

    def add_before(self, finding_id, rule_id, source, needle, symbol=None, snippet=True, path=DEFAULT_PATH):
        self.before.append({
            "id": finding_id,
            "ruleId": rule_id,
            "filePath": path,
            "symbolPath": symbol,
            "line": line_of(source, needle),
            "snippet": snippet_of(source, needle) if snippet else None,
        })
        return self

    def add_after(self, finding_id, rule_id, source, needle, symbol=None, snippet=True, path=DEFAULT_PATH):
        self.after.append({
            "id": finding_id,
            "ruleId": rule_id,
            "filePath": path,
            "symbolPath": symbol,
            "line": line_of(source, needle),
            "snippet": snippet_of(source, needle) if snippet else None,
        })
        return self

    def expect(self, before_id, after_id):
        self.expected.append({"before": before_id, "after": after_id})
        return self

    def rename(self, old_path, new_path):
        self.renames[old_path] = new_path
        return self

    def to_json(self):
        before_ids = {f["id"] for f in self.before}
        after_ids = {f["id"] for f in self.after}
        for pair in self.expected:
            assert pair["before"] in before_ids, f"{self.case_id}: unknown before id {pair['before']}"
            assert pair["after"] in after_ids, f"{self.case_id}: unknown after id {pair['after']}"
        return {
            "id": self.case_id,
            "description": self.description,
            "refactorShape": self.refactor_shape,
            "renames": self.renames,
            "before": self.before,
            "after": self.after,
            "expectedMatches": self.expected,
        }


CASES = []


def add(c: Case):
    CASES.append(c)
    return c


# ---------------------------------------------------------------------------------------------
# 1-2: extract method
# ---------------------------------------------------------------------------------------------

c = Case("01-extract-method-preserves-flagged-line",
         "The flagged statement is lifted verbatim into a newly extracted method; the enclosing "
         "symbol changes but the line's own text does not, so context_fp (rung 2) survives even "
         "though identity_fp (rung 1) cannot.",
         "extract-method")
before_src = """package com.acme;

public class PaymentService {
    public void refund(Order order) {
        validate(order);
        String sql = "SELECT * FROM refunds WHERE id = " + order.getId();
        jdbcTemplate.query(sql);
    }
}
"""
after_src = """package com.acme;

public class PaymentService {
    public void refund(Order order) {
        validate(order);
        String sql = buildRefundQuery(order);
        jdbcTemplate.query(sql);
    }

    private String buildRefundQuery(Order order) {
        String sql = "SELECT * FROM refunds WHERE id = " + order.getId();
        return sql;
    }
}
"""
c.add_before("b1", "java:S3649", before_src, 'String sql = "SELECT * FROM refunds',
              symbol="com.acme.PaymentService#refund")
c.add_after("a1", "java:S3649", after_src, 'String sql = "SELECT * FROM refunds',
             symbol="com.acme.PaymentService#buildRefundQuery")
c.expect("b1", "a1")
add(c)

c = Case("02-extract-method-no-symbol-path",
         "Same extraction as case 1, but the analyser does not supply logicalLocations at all "
         "(a real constraint for some SARIF producers, §3.2) - identity_fp is unavailable on both "
         "sides, so this exercises the fallback to context_fp directly.",
         "extract-method")
c.add_before("b1", "java:S3649", before_src, 'String sql = "SELECT * FROM refunds', symbol=None)
c.add_after("a1", "java:S3649", after_src, 'String sql = "SELECT * FROM refunds', symbol=None)
c.expect("b1", "a1")
add(c)

# ---------------------------------------------------------------------------------------------
# 3-4: rename symbol
# ---------------------------------------------------------------------------------------------

c = Case("03-rename-local-variable",
         "A local variable is renamed; the line that uses it changes, but the enclosing method "
         "does not, so identity_fp (rung 1) survives a change that defeats context_fp (rung 2). "
         "This is the table's own claim in §3.2 - identity_fp 'survives renamed locals'.",
         "rename-symbol")
before_src = """package com.acme;

public class PaymentService {
    public void issueRefund(Order order) {
        String sql = "SELECT * FROM refunds WHERE id = " + o.getId();
        jdbcTemplate.query(sql);
    }
}
"""
after_src = """package com.acme;

public class PaymentService {
    public void issueRefund(Order order) {
        String sql = "SELECT * FROM refunds WHERE id = " + order.getId();
        jdbcTemplate.query(sql);
    }
}
"""
c.add_before("b1", "java:S3649", before_src, 'String sql = "SELECT * FROM refunds',
              symbol="com.acme.PaymentService#issueRefund")
c.add_after("a1", "java:S3649", after_src, 'String sql = "SELECT * FROM refunds',
             symbol="com.acme.PaymentService#issueRefund")
c.expect("b1", "a1")
add(c)

c = Case("04-rename-enclosing-method-only",
         "The enclosing method is renamed, but the flagged line's own text is untouched - the "
         "mirror image of case 3. identity_fp (rung 1) breaks (§3.2: 'breaks on renamed enclosing "
         "method/class'); context_fp (rung 2) survives because the line itself did not change.",
         "rename-symbol")
before_src = """package com.acme;

public class PaymentService {
    public void refund(Order order) {
        String sql = "SELECT * FROM refunds WHERE id = " + order.getId();
        jdbcTemplate.query(sql);
    }
}
"""
after_src = """package com.acme;

public class PaymentService {
    public void issueRefund(Order order) {
        String sql = "SELECT * FROM refunds WHERE id = " + order.getId();
        jdbcTemplate.query(sql);
    }
}
"""
c.add_before("b1", "java:S3649", before_src, 'String sql = "SELECT * FROM refunds',
              symbol="com.acme.PaymentService#refund")
c.add_after("a1", "java:S3649", after_src, 'String sql = "SELECT * FROM refunds',
             symbol="com.acme.PaymentService#issueRefund")
c.expect("b1", "a1")
add(c)

# ---------------------------------------------------------------------------------------------
# 5, 27: reorder / add imports
# ---------------------------------------------------------------------------------------------

c = Case("05-reorder-and-add-imports",
         "Twelve lines of imports are added and reordered ahead of the flagged code, shifting "
         "every subsequent line - exactly ARCHITECTURE.md §3.1's own motivating example, with the "
         "method and its parameter left alone this time so identity_fp (rung 1) resolves it "
         "cleanly.",
         "reorder-imports")
before_src = """package com.acme;

import java.util.List;

public class PaymentService {
    public void refund(Order order) {
        String sql = "SELECT * FROM refunds WHERE id = " + order.getId();
    }
}
"""
after_src = """package com.acme;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class PaymentService {
    public void refund(Order order) {
        String sql = "SELECT * FROM refunds WHERE id = " + order.getId();
    }
}
"""
c.add_before("b1", "java:S3649", before_src, 'String sql = "SELECT * FROM refunds',
              symbol="com.acme.PaymentService#refund")
c.add_after("a1", "java:S3649", after_src, 'String sql = "SELECT * FROM refunds',
             symbol="com.acme.PaymentService#refund")
c.expect("b1", "a1")
add(c)

c = Case("27-reorder-imports-no-symbol-path",
         "Same import churn as case 5, but without a symbol path, forcing the match through "
         "context_fp on the unchanged flagged line.",
         "reorder-imports")
c.add_before("b1", "java:S3649", before_src, 'String sql = "SELECT * FROM refunds', symbol=None)
c.add_after("a1", "java:S3649", after_src, 'String sql = "SELECT * FROM refunds', symbol=None)
c.expect("b1", "a1")
add(c)

# ---------------------------------------------------------------------------------------------
# 6-7, 24: reformat
# ---------------------------------------------------------------------------------------------

c = Case("06-reformat-indentation-with-symbol-path",
         "A pure formatting pass (four-space to tab indentation) touches the flagged line; "
         "identity_fp is available and unaffected by any of it.",
         "reformat")
before_src = """package com.acme;

public class PaymentService {
    public void refund(Order order) {
        String sql = "SELECT * FROM refunds WHERE id = " + order.getId();
    }
}
"""
after_src = """package com.acme;

public class PaymentService {
\tpublic void refund(Order order) {
\t\tString sql = "SELECT * FROM refunds WHERE id = " + order.getId();
\t}
}
"""
c.add_before("b1", "java:S3649", before_src, 'String sql = "SELECT * FROM refunds',
              symbol="com.acme.PaymentService#refund")
c.add_after("a1", "java:S3649", after_src, 'String sql = "SELECT * FROM refunds',
             symbol="com.acme.PaymentService#refund")
c.expect("b1", "a1")
add(c)

c = Case("07-reformat-no-symbol-path",
         "The same reformatting pass as case 6, but the analyser does not supply a symbol path - "
         "this is the fixture that exercises context_fp's whitespace tolerance directly, since "
         "leading/trailing whitespace is exactly what §3.2's normalisation strips.",
         "reformat")
c.add_before("b1", "java:S3649", before_src, 'String sql = "SELECT * FROM refunds', symbol=None)
c.add_after("a1", "java:S3649", after_src, 'String sql = "SELECT * FROM refunds', symbol=None)
c.expect("b1", "a1")
add(c)

c = Case("24-reformat-whole-file-line-shift",
         "A formatter adds blank lines and braces-on-own-line spacing throughout the file, "
         "shifting the flagged line by several positions with the enclosing method preserved.",
         "reformat")
before_src = """package com.acme;
public class PaymentService {
    public void refund(Order order) {
        String sql = "SELECT * FROM refunds WHERE id = " + order.getId();
    }
}
"""
after_src = """package com.acme;

public class PaymentService
{

    public void refund(Order order)
    {
        String sql = "SELECT * FROM refunds WHERE id = " + order.getId();
    }

}
"""
c.add_before("b1", "java:S3649", before_src, 'String sql = "SELECT * FROM refunds',
              symbol="com.acme.PaymentService#refund")
c.add_after("a1", "java:S3649", after_src, 'String sql = "SELECT * FROM refunds',
             symbol="com.acme.PaymentService#refund")
c.expect("b1", "a1")
add(c)

# ---------------------------------------------------------------------------------------------
# 8-10, 26: move / rename file
# ---------------------------------------------------------------------------------------------

c = Case("08-move-file-with-symbol-path",
         "A pure `git mv`: the file moves to a new directory, the class keeps its package and "
         "name. Once the SCM rename map is applied to the previous finding's path (§3.2), "
         "identity_fp matches on the (now-identical) normalised path and symbol path.",
         "move-file")
c.add_before("b1", "java:S3649", before_src, 'String sql = "SELECT * FROM refunds',
              symbol="com.acme.PaymentService#refund",
              path="src/main/java/com/acme/legacy/PaymentService.java")
c.add_after("a1", "java:S3649", before_src, 'String sql = "SELECT * FROM refunds',
             symbol="com.acme.PaymentService#refund",
             path="src/main/java/com/acme/billing/PaymentService.java")
c.rename("src/main/java/com/acme/legacy/PaymentService.java",
         "src/main/java/com/acme/billing/PaymentService.java")
c.expect("b1", "a1")
add(c)

c = Case("09-move-file-no-symbol-path",
         "The same move as case 8, without a symbol path: matching now depends on the renamed "
         "path plus the unchanged line content, i.e. context_fp.",
         "move-file")
c.add_before("b1", "java:S3649", before_src, 'String sql = "SELECT * FROM refunds', symbol=None,
              path="src/main/java/com/acme/legacy/PaymentService.java")
c.add_after("a1", "java:S3649", before_src, 'String sql = "SELECT * FROM refunds', symbol=None,
             path="src/main/java/com/acme/billing/PaymentService.java")
c.rename("src/main/java/com/acme/legacy/PaymentService.java",
         "src/main/java/com/acme/billing/PaymentService.java")
c.expect("b1", "a1")
add(c)

c = Case("10-move-file-without-rename-resolution",
         "A documented limitation, not a bug: the file moved, but the SCM rename map could not be "
         "resolved for this run (the compare API was unreachable, or the run has no base commit to "
         "diff against - GitHubScmRenameResolver degrades to an empty map rather than failing the "
         "run). With no renamed path to match against, the old issue is auto-resolved as fixed and "
         "the finding at the new path opens as a new issue. This is the expected, graceful "
         "degradation §3.2 describes, and matcher-corpus is where that trade-off is pinned down "
         "rather than left as an assumption.",
         "move-file")
c.add_before("b1", "java:S3649", before_src, 'String sql = "SELECT * FROM refunds', symbol=None,
              path="src/main/java/com/acme/legacy/PaymentService.java")
c.add_after("a1", "java:S3649", before_src, 'String sql = "SELECT * FROM refunds', symbol=None,
             path="src/main/java/com/acme/billing/PaymentService.java")
# Deliberately no c.rename(...) call - this is the "resolution failed" case.
add(c)

c = Case("26-move-file-and-rename-class-combined",
         "The file moves *and* the class is renamed in the same commit. The rename map fixes the "
         "path; identity_fp still breaks on the class rename (consistent with case 4's logic, "
         "just at class rather than method granularity), so this resolves through context_fp.",
         "move-file")
before_src2 = """package com.acme.legacy;

public class LegacyPaymentService {
    public void refund(Order order) {
        String sql = "SELECT * FROM refunds WHERE id = " + order.getId();
    }
}
"""
after_src2 = """package com.acme.billing;

public class PaymentService {
    public void refund(Order order) {
        String sql = "SELECT * FROM refunds WHERE id = " + order.getId();
    }
}
"""
c.add_before("b1", "java:S3649", before_src2, 'String sql = "SELECT * FROM refunds',
              symbol="com.acme.legacy.LegacyPaymentService#refund",
              path="src/main/java/com/acme/legacy/LegacyPaymentService.java")
c.add_after("a1", "java:S3649", after_src2, 'String sql = "SELECT * FROM refunds',
             symbol="com.acme.billing.PaymentService#refund",
             path="src/main/java/com/acme/billing/PaymentService.java")
c.rename("src/main/java/com/acme/legacy/LegacyPaymentService.java",
         "src/main/java/com/acme/billing/PaymentService.java")
c.expect("b1", "a1")
add(c)

# ---------------------------------------------------------------------------------------------
# 11-12: inline variable
# ---------------------------------------------------------------------------------------------

c = Case("11-inline-variable-with-symbol-path",
         "A local variable is inlined into its single use site, substantially rewriting the "
         "flagged line's text while leaving the enclosing method alone - identity_fp survives a "
         "change context_fp cannot.",
         "inline-variable")
before_src = """package com.acme;

public class OrderService {
    public Order findOrder(String id) {
        Order raw = repository.find(id);
        return raw.normalise();
    }
}
"""
after_src = """package com.acme;

public class OrderService {
    public Order findOrder(String id) {
        return repository.find(id).normalise();
    }
}
"""
c.add_before("b1", "java:S2259", before_src, "return raw.normalise();",
              symbol="com.acme.OrderService#findOrder", path=ORDER_SERVICE_PATH)
c.add_after("a1", "java:S2259", after_src, "return repository.find(id).normalise();",
             symbol="com.acme.OrderService#findOrder", path=ORDER_SERVICE_PATH)
c.expect("b1", "a1")
add(c)

c = Case("12-inline-variable-no-symbol-path",
         "The same inlining as case 11, without a symbol path. The flagged line's text changed "
         "enough to defeat context_fp too (the whole right-hand side is different), so this "
         "exercises weak_fp: same rule, same file, a one-line shift well inside the proximity "
         "window.",
         "inline-variable")
c.add_before("b1", "java:S2259", before_src, "return raw.normalise();", symbol=None, path=ORDER_SERVICE_PATH)
c.add_after("a1", "java:S2259", after_src, "return repository.find(id).normalise();", symbol=None,
             path=ORDER_SERVICE_PATH)
c.expect("b1", "a1")
add(c)

# ---------------------------------------------------------------------------------------------
# 13-14: wrap in try/catch
# ---------------------------------------------------------------------------------------------

c = Case("13-wrap-in-try-catch-with-symbol-path",
         "The flagged line is wrapped in a new try/catch block, shifting it by one line without "
         "changing its text or its enclosing method.",
         "wrap-in-try-catch")
before_src = """package com.acme;

public class OrderService {
    public void refresh() {
        repository.reload();
    }
}
"""
after_src = """package com.acme;

public class OrderService {
    public void refresh() {
        try {
            repository.reload();
        } catch (DataAccessException e) {
            log.warn("refresh failed", e);
        }
    }
}
"""
c.add_before("b1", "java:S1181", before_src, "repository.reload();",
              symbol="com.acme.OrderService#refresh", path=ORDER_SERVICE_PATH)
c.add_after("a1", "java:S1181", after_src, "repository.reload();",
             symbol="com.acme.OrderService#refresh", path=ORDER_SERVICE_PATH)
c.expect("b1", "a1")
add(c)

c = Case("14-wrap-in-try-catch-no-symbol-path",
         "Same wrapping as case 13, without a symbol path - resolved by context_fp since the "
         "flagged line's own text is untouched.",
         "wrap-in-try-catch")
c.add_before("b1", "java:S1181", before_src, "repository.reload();", symbol=None, path=ORDER_SERVICE_PATH)
c.add_after("a1", "java:S1181", after_src, "repository.reload();", symbol=None, path=ORDER_SERVICE_PATH)
c.expect("b1", "a1")
add(c)

# ---------------------------------------------------------------------------------------------
# 15-16: add overload (and the false-merge trap)
# ---------------------------------------------------------------------------------------------

c = Case("15-add-overload-shifts-existing-method",
         "A new overload is inserted above an existing, already-flagged method, shifting the "
         "existing method down without changing it. identity_fp's symbol path is signature-scoped "
         "(§3.2: 'com.acme.PaymentService#issueRefund'), so it is untouched by a sibling method "
         "appearing above it.",
         "add-overload")
before_src = """package com.acme;

public class PaymentService {
    public void refund(Order order) {
        String sql = "SELECT * FROM refunds WHERE id = " + order.getId();
    }
}
"""
after_src = """package com.acme;

public class PaymentService {
    public void refund(Order order, String reason) {
        audit.log(reason);
        refund(order);
    }

    public void refund(Order order) {
        String sql = "SELECT * FROM refunds WHERE id = " + order.getId();
    }
}
"""
c.add_before("b1", "java:S3649", before_src, 'String sql = "SELECT * FROM refunds',
              symbol="com.acme.PaymentService#refund(Order)")
c.add_after("a1", "java:S3649", after_src, 'String sql = "SELECT * FROM refunds',
             symbol="com.acme.PaymentService#refund(Order)")
c.expect("b1", "a1")
add(c)

c = Case("16-add-overload-does-not-merge-with-sibling",
         "The false-merge stress case: two overloads of the same method already carry their own, "
         "distinct issues (S1172 unused parameter) before a *third* overload is added. Signature-"
         "scoped symbol paths must keep all three apart - collapsing any two of them into one "
         "issue, or attaching the new overload's finding to either existing issue, is exactly the "
         "failure matcher-corpus exists to catch.",
         "add-overload")
before_src = """package com.acme;

public class NotificationService {
    public void notify(User user) {
        send(user, defaultTemplate());
    }

    public void notify(User user, Template template) {
        send(user, template);
    }
}
"""
after_src = """package com.acme;

public class NotificationService {
    public void notify(User user) {
        send(user, defaultTemplate());
    }

    public void notify(User user, Template template) {
        send(user, template);
    }

    public void notify(User user, Template template, boolean urgent) {
        send(user, template);
    }
}
"""
c.add_before("b1", "java:S1172", before_src, "public void notify(User user) {",
              symbol="com.acme.NotificationService#notify(User)", path=NOTIFICATION_PATH)
c.add_before("b2", "java:S1172", before_src, "public void notify(User user, Template template) {",
              symbol="com.acme.NotificationService#notify(User,Template)", path=NOTIFICATION_PATH)
c.add_after("a1", "java:S1172", after_src, "public void notify(User user) {",
             symbol="com.acme.NotificationService#notify(User)", path=NOTIFICATION_PATH)
c.add_after("a2", "java:S1172", after_src, "public void notify(User user, Template template) {",
             symbol="com.acme.NotificationService#notify(User,Template)", path=NOTIFICATION_PATH)
c.add_after("a3", "java:S1172", after_src,
             "public void notify(User user, Template template, boolean urgent) {",
             symbol="com.acme.NotificationService#notify(User,Template,boolean)", path=NOTIFICATION_PATH)
c.expect("b1", "a1")
c.expect("b2", "a2")
# a3 has no expected match: it is a genuinely new issue on the new overload.
add(c)

# ---------------------------------------------------------------------------------------------
# 17-18: change literal
# ---------------------------------------------------------------------------------------------

c = Case("17-change-string-literal-no-symbol-path",
         "ARCHITECTURE.md §3.2's own example, end to end: a string and a numeric literal both "
         "change on the flagged line. No symbol path, so this is squarely a context_fp case.",
         "change-literal")
before_src = """package com.acme;

public class Pricing {
    public double apply(Order order) {
        double x = base("abc") + 1;
        return x;
    }
}
"""
after_src = """package com.acme;

public class Pricing {
    public double apply(Order order) {
        double x = base("def") + 2;
        return x;
    }
}
"""
c.add_before("b1", "java:S2184", before_src, 'double x = base("abc") + 1;', symbol=None, path=PRICING_PATH)
c.add_after("a1", "java:S2184", after_src, 'double x = base("def") + 2;', symbol=None, path=PRICING_PATH)
c.expect("b1", "a1")
add(c)

c = Case("18-change-numeric-threshold-with-symbol-path",
         "A magic-number threshold changes; identity_fp is available and, being independent of "
         "line content entirely, is untouched.",
         "change-literal")
before_src = """package com.acme;

public class Pricing {
    public boolean isHighValue(Order order) {
        return order.total() > 1000;
    }
}
"""
after_src = """package com.acme;

public class Pricing {
    public boolean isHighValue(Order order) {
        return order.total() > 5000;
    }
}
"""
c.add_before("b1", "java:S109", before_src, "return order.total() > 1000;",
              symbol="com.acme.Pricing#isHighValue", path=PRICING_PATH)
c.add_after("a1", "java:S109", after_src, "return order.total() > 5000;",
             symbol="com.acme.Pricing#isHighValue", path=PRICING_PATH)
c.expect("b1", "a1")
add(c)

# ---------------------------------------------------------------------------------------------
# 19: minimal SARIF (no symbol path, no snippet at all)
# ---------------------------------------------------------------------------------------------

c = Case("19-minimal-analyser-weak-fp-only",
         "A minimal analyser output: no logicalLocations, no region.snippet.text, just a rule, a "
         "file and a line. Only weak_fp is ever computable for either side, so this is the purest "
         "test of rung 3 on its own - reusing case 18's unchanged line number, so the match is on "
         "rule+file alone with zero line movement to help it.",
         "reformat")
c.add_before("b1", "java:S1135", before_src, "public boolean isHighValue(Order order) {",
              symbol=None, snippet=False, path=PRICING_PATH)
c.add_after("a1", "java:S1135", after_src, "public boolean isHighValue(Order order) {",
             symbol=None, snippet=False, path=PRICING_PATH)
c.expect("b1", "a1")
add(c)

# ---------------------------------------------------------------------------------------------
# 20-21: genuinely new / genuinely fixed
# ---------------------------------------------------------------------------------------------

c = Case("20-genuinely-new-issue",
         "A commit introduces a real defect with no prior counterpart anywhere in the baseline. "
         "The most basic new-issue case, and a sanity check that an empty baseline never invents "
         "a match out of nothing.",
         "other")
after_src = """package com.acme;

public class Pricing {
    public double apply(Order order) {
        double x = order.total() / discountRate;
        return x;
    }
}
"""
c.add_after("a1", "java:S3518", after_src, "double x = order.total() / discountRate;",
             symbol="com.acme.Pricing#apply", path=PRICING_PATH)
# No before findings at all, and no expected matches.
add(c)

c = Case("21-genuinely-fixed-issue",
         "The defect is actually fixed - the offending line is removed outright, not moved or "
         "rewritten - and the run reports no findings at all for this rule. The previous issue "
         "must be reported as no-longer-present (auto-resolved fixed), not silently dropped.",
         "other")
before_src = """package com.acme;

public class Pricing {
    public double apply(Order order) {
        double x = order.total() / discountRate;
        return x;
    }
}
"""
c.add_before("b1", "java:S3518", before_src, "double x = order.total() / discountRate;",
              symbol="com.acme.Pricing#apply", path=PRICING_PATH)
# No after findings, and no expected matches.
add(c)

# ---------------------------------------------------------------------------------------------
# 22-23: multi-issue stress cases
# ---------------------------------------------------------------------------------------------

c = Case("22-multiple-distinct-issues-one-file",
         "Four unrelated issues in the same file, three different rules, with an unrelated method "
         "added in between. Every issue must land on its own counterpart; none may cross-attach to "
         "a different line or rule.",
         "other")
before_src = """package com.acme;

public class OrderService {
    public Order findOrder(String id) {
        Order order = repository.find(id);
        return order.normalise();
    }

    public double total(List<Order> orders) {
        double sum = 0;
        for (Order order : orders) {
            sum += order.amount();
        }
        return sum;
    }

    public void refresh() {
        try {
            repository.reload();
        } catch (Exception e) {
            return;
        }
    }
}
"""
after_src = """package com.acme;

public class OrderService {
    public Order findOrder(String id) {
        Order order = repository.find(id);
        return order.normalise();
    }

    public Order latest() {
        return repository.latest().normalise();
    }

    public double total(List<Order> orders) {
        double sum = 0;
        for (Order order : orders) {
            sum += order.amount();
        }
        return sum;
    }

    public void refresh() {
        try {
            repository.reload();
        } catch (Exception e) {
            return;
        }
    }
}
"""
c.add_before("b1", "java:S2259", before_src, "return order.normalise();",
              symbol="com.acme.OrderService#findOrder", path=ORDER_SERVICE_PATH)
c.add_before("b2", "java:S2184", before_src, "sum += order.amount();",
              symbol="com.acme.OrderService#total", path=ORDER_SERVICE_PATH)
c.add_before("b3", "java:S1181", before_src, "} catch (Exception e) {",
              symbol="com.acme.OrderService#refresh", path=ORDER_SERVICE_PATH)
c.add_after("a1", "java:S2259", after_src, "return order.normalise();",
             symbol="com.acme.OrderService#findOrder", path=ORDER_SERVICE_PATH)
c.add_after("a2", "java:S2259", after_src, "return repository.latest().normalise();",
             symbol="com.acme.OrderService#latest", path=ORDER_SERVICE_PATH)
c.add_after("a3", "java:S2184", after_src, "sum += order.amount();",
             symbol="com.acme.OrderService#total", path=ORDER_SERVICE_PATH)
c.add_after("a4", "java:S1181", after_src, "} catch (Exception e) {",
             symbol="com.acme.OrderService#refresh", path=ORDER_SERVICE_PATH)
c.expect("b1", "a1")
c.expect("b2", "a3")
c.expect("b3", "a4")
# a2 (the new latest() method) has no prior counterpart - it is genuinely new.
add(c)

c = Case("23-same-line-shift-different-rules-stay-separate",
         "Two different rules are both flagged on adjacent lines and both shift by the same "
         "amount when a field is added above them. Fingerprints always include the rule id, so "
         "even identical shifts on neighbouring lines cannot cross-attach - this fixture makes "
         "that an explicit, checked property rather than an assumption.",
         "other")
before_src = """package com.acme;

public class Pricing {
    public double apply(Order order) {
        double x = order.total() / discountRate;
        boolean flagged = x > 1000;
        return x;
    }
}
"""
after_src = """package com.acme;

public class Pricing {
    private final Clock clock;

    public double apply(Order order) {
        double x = order.total() / discountRate;
        boolean flagged = x > 1000;
        return x;
    }
}
"""
c.add_before("b1", "java:S3518", before_src, "double x = order.total() / discountRate;",
              symbol=None, path=PRICING_PATH)
c.add_before("b2", "java:S109", before_src, "boolean flagged = x > 1000;", symbol=None, path=PRICING_PATH)
c.add_after("a1", "java:S3518", after_src, "double x = order.total() / discountRate;", symbol=None,
             path=PRICING_PATH)
c.add_after("a2", "java:S109", after_src, "boolean flagged = x > 1000;", symbol=None, path=PRICING_PATH)
c.expect("b1", "a1")
c.expect("b2", "a2")
add(c)

# ---------------------------------------------------------------------------------------------
# 25: the weak_fp tie-break trap
# ---------------------------------------------------------------------------------------------

c = Case("25-weak-fp-tie-break-trap",
         "Two previous issues of the same rule in the same file, sixty-odd lines apart - far "
         "enough that their +-25-line proximity windows (§3.2) cannot overlap - and a single field "
         "added at the top of the class shifts both current findings down by exactly one line. No "
         "symbol path or snippet on either side, so this is decided purely by weak_fp plus line "
         "proximity: each current finding is unambiguously closer to its own true origin than to "
         "the other, and it must not swap the pairing.",
         "other")
filler = "\n".join(f"    // filler line {i}" for i in range(1, 61))
before_src = f"""package com.acme;

public class BatchProcessor {{
    public void stepOne() {{
        // TODO harden stepOne before this ships
    }}

{filler}

    public void stepNine() {{
        // TODO harden stepNine before this ships
    }}
}}
"""
after_src = f"""package com.acme;

public class BatchProcessor {{
    private final Clock clock;

    public void stepOne() {{
        // TODO harden stepOne before this ships
    }}

{filler}

    public void stepNine() {{
        // TODO harden stepNine before this ships
    }}
}}
"""
c.add_before("b1", "java:S1135", before_src, "// TODO harden stepOne before this ships",
              symbol=None, snippet=False, path=BIG_FILE_PATH)
c.add_before("b2", "java:S1135", before_src, "// TODO harden stepNine before this ships",
              symbol=None, snippet=False, path=BIG_FILE_PATH)
c.add_after("a1", "java:S1135", after_src, "// TODO harden stepOne before this ships",
             symbol=None, snippet=False, path=BIG_FILE_PATH)
c.add_after("a2", "java:S1135", after_src, "// TODO harden stepNine before this ships",
             symbol=None, snippet=False, path=BIG_FILE_PATH)
c.expect("b1", "a1")
c.expect("b2", "a2")
add(c)

# ---------------------------------------------------------------------------------------------
# 28: large in-file jump beyond the proximity window (documented limitation)
# ---------------------------------------------------------------------------------------------

c = Case("28-large-in-file-jump-exceeds-proximity",
         "A block is cut and pasted from the top of a large file to the bottom, 38 lines away, for "
         "a rule whose analyser reports neither a symbol path nor a line snippet (identity_fp and "
         "context_fp are therefore both unavailable - not merely defeated - so only weak_fp is ever "
         "in play; this is not a file rename either, so the SCM rename map does not apply). Rung "
         "3's proximity window (25 lines, §3.2) is a deliberate, documented boundary: beyond it, "
         "Vestige reports the move as fixed-and-new rather than guessing. Getting this wrong in the "
         "other direction - matching arbitrarily distant lines - is exactly how weak_fp would stop "
         "being trustworthy. (Contrast case 12, the same boundary well inside the window.)",
         "other")
padding = "\n".join(f"    // padding line {i}" for i in range(1, 39))
before_src = f"""package com.acme;

public class BigFile {{
    public void doWork() {{
        legacyHelper();
    }}
{padding}
}}
"""
after_src = f"""package com.acme;

public class BigFile {{
{padding}
    public void doWork() {{
        legacyHelper();
    }}
}}
"""
c.add_before("b1", "java:S1181", before_src, "legacyHelper();", symbol=None, snippet=False, path=BIG_FILE_PATH)
c.add_after("a1", "java:S1181", after_src, "legacyHelper();", symbol=None, snippet=False, path=BIG_FILE_PATH)
# Deliberately no expected match - see description.
add(c)

# ---------------------------------------------------------------------------------------------
# 29: unrelated new issue alongside untouched existing ones
# ---------------------------------------------------------------------------------------------

c = Case("29-unrelated-new-issue-alongside-untouched-ones",
         "A commit that only adds new, unrelated code. Every pre-existing issue must be left "
         "exactly as it was; the new method's own issue must not attach to any of them.",
         "other")
before_src = """package com.acme;

public class ReportGenerator {
    public String header(Report report) {
        String title = "Report: " + report.name();
        return title;
    }
}
"""
after_src = """package com.acme;

public class ReportGenerator {
    public String header(Report report) {
        String title = "Report: " + report.name();
        return title;
    }

    public String footer(Report report) {
        String stamp = "Generated " + report.timestamp();
        return stamp;
    }
}
"""
c.add_before("b1", "java:S1192", before_src, 'String title = "Report: " + report.name();',
              symbol="com.acme.ReportGenerator#header", path=REPORT_GENERATOR_PATH)
c.add_after("a1", "java:S1192", after_src, 'String title = "Report: " + report.name();',
             symbol="com.acme.ReportGenerator#header", path=REPORT_GENERATOR_PATH)
c.add_after("a2", "java:S1192", after_src, 'String stamp = "Generated " + report.timestamp();',
             symbol="com.acme.ReportGenerator#footer", path=REPORT_GENERATOR_PATH)
c.expect("b1", "a1")
add(c)

# ---------------------------------------------------------------------------------------------
# 30: signature change that does not touch the parameter list
# ---------------------------------------------------------------------------------------------

c = Case("30-add-throws-clause",
         "A checked exception is added to a method's throws clause - a real, common refactor that "
         "changes the method's signature without changing its name or parameter types. Symbol "
         "paths are parameter-type-scoped (§3.2), not full-signature-scoped, so identity_fp is "
         "unaffected.",
         "rename-symbol")
before_src = """package com.acme;

public class PaymentService {
    public void refund(Order order) {
        String sql = "SELECT * FROM refunds WHERE id = " + order.getId();
    }
}
"""
after_src = """package com.acme;

public class PaymentService {
    public void refund(Order order) throws PaymentException {
        String sql = "SELECT * FROM refunds WHERE id = " + order.getId();
    }
}
"""
c.add_before("b1", "java:S3649", before_src, 'String sql = "SELECT * FROM refunds',
              symbol="com.acme.PaymentService#refund(Order)")
c.add_after("a1", "java:S3649", after_src, 'String sql = "SELECT * FROM refunds',
             symbol="com.acme.PaymentService#refund(Order)")
c.expect("b1", "a1")
add(c)

# ---------------------------------------------------------------------------------------------
# 31: comment-only change above the flagged line
# ---------------------------------------------------------------------------------------------

c = Case("31-add-javadoc-comment",
         "A Javadoc comment is added above the flagged method, shifting its line number without "
         "touching the method or the flagged line - about as common and as low-stakes a commit as "
         "exists, and one naive line-number matching gets wrong every time.",
         "reformat")
before_src = """package com.acme;

public class PaymentService {
    public void refund(Order order) {
        String sql = "SELECT * FROM refunds WHERE id = " + order.getId();
    }
}
"""
after_src = """package com.acme;

public class PaymentService {
    /**
     * Refunds the given order in full.
     *
     * @param order the order to refund
     */
    public void refund(Order order) {
        String sql = "SELECT * FROM refunds WHERE id = " + order.getId();
    }
}
"""
c.add_before("b1", "java:S3649", before_src, 'String sql = "SELECT * FROM refunds',
              symbol="com.acme.PaymentService#refund(Order)")
c.add_after("a1", "java:S3649", after_src, 'String sql = "SELECT * FROM refunds',
             symbol="com.acme.PaymentService#refund(Order)")
c.expect("b1", "a1")
add(c)

# ---------------------------------------------------------------------------------------------
# 32: a realistic mixed commit (capstone)
# ---------------------------------------------------------------------------------------------

c = Case("32-realistic-mixed-commit",
         "A capstone fixture combining several shapes in one commit, the way a real pull request "
         "usually looks: an import added (shifts everything), one method's local variable renamed "
         "(identity_fp still resolves it), a second, unrelated issue fixed outright, and a third, "
         "brand new issue introduced. All four outcomes must be classified correctly in the same "
         "matching pass.",
         "other")
before_src = """package com.acme;

import java.util.List;

public class OrderService {
    public Order findOrder(String id) {
        Order o = repository.find(id);
        return o.normalise();
    }

    public double total(List<Order> orders) {
        double sum = 0;
        for (Order order : orders) {
            sum += order.amount();
        }
        return sum;
    }
}
"""
after_src = """package com.acme;

import java.util.List;
import java.util.Optional;

public class OrderService {
    public Order findOrder(String id) {
        Order order = repository.find(id);
        return order.normalise();
    }

    public Optional<Order> latest() {
        return Optional.ofNullable(repository.latest());
    }
}
"""
c.add_before("b1", "java:S2259", before_src, "return o.normalise();",
              symbol="com.acme.OrderService#findOrder", path=ORDER_SERVICE_PATH)
c.add_before("b2", "java:S2184", before_src, "sum += order.amount();",
              symbol="com.acme.OrderService#total", path=ORDER_SERVICE_PATH)
c.add_after("a1", "java:S2259", after_src, "return order.normalise();",
             symbol="com.acme.OrderService#findOrder", path=ORDER_SERVICE_PATH)
c.add_after("a2", "java:S3655", after_src, "return Optional.ofNullable(repository.latest());",
             symbol="com.acme.OrderService#latest", path=ORDER_SERVICE_PATH)
c.expect("b1", "a1")
# b2 (in total(), now deleted) is genuinely fixed; a2 (in the new latest()) is genuinely new.
add(c)


def main():
    CASES_DIR.mkdir(parents=True, exist_ok=True)
    for existing in CASES_DIR.glob("*.json"):
        existing.unlink()

    ids = [c.case_id for c in CASES]
    assert len(ids) == len(set(ids)), "duplicate case ids"

    for c in CASES:
        payload = c.to_json()
        path = CASES_DIR / f"{c.case_id}.json"
        path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    total_before = sum(len(c.before) for c in CASES)
    total_after = sum(len(c.after) for c in CASES)
    total_expected = sum(len(c.expected) for c in CASES)
    print(f"wrote {len(CASES)} cases to {CASES_DIR}")
    print(f"total before findings: {total_before}")
    print(f"total after findings:  {total_after}")
    print(f"total expected matches: {total_expected}")


if __name__ == "__main__":
    main()
