"""Xen's taste in buildings, learned from the ratings people give.

The rater judges general qualities.  Taste adds what *you* like: every build
carries tags (its roof style, palette, template), and when you rate a build
Xen compares your score with what it predicted and nudges its preferences for
those tags and criteria (a small online regression).  Over time its designs
drift towards your style.
"""
import json
import os

from .rating import DEFAULT_WEIGHTS, score


class Taste:
    def __init__(self, path=None, lr=0.15):
        self.path = path
        self.lr = lr
        self.weights = dict(DEFAULT_WEIGHTS)       # how much each quality matters
        self.tags = {}                              # style preferences, in score points
        self.ratings = 0
        self.portfolio = []                         # best designs so far
        if path and os.path.exists(path):
            self.load(path)

    def predict(self, rating, tags=(), shelter=True):
        base = score(rating.criteria, self.weights, shelter)
        bonus = sum(self.tags.get(t, 0.0) for t in tags)
        return max(0.0, min(10.0, base + bonus))

    def learn(self, rating, tags, human_score, shelter=True):
        """Someone rated this build `human_score` (0..10): adjust taste towards it."""
        error = float(human_score) - self.predict(rating, tags, shelter)
        for t in tags:
            self.tags[t] = max(-5.0, min(5.0, self.tags.get(t, 0.0) + self.lr * error))
        # Criteria that are strong in a build you love get more weight (and vice versa).
        for k in self.weights:
            self.weights[k] = max(0.05, min(5.0, self.weights[k] + 0.1 * self.lr * error * (rating.criteria[k] - 0.5)))
        self.ratings += 1
        return error

    def favourite(self, prefix):
        """The best-liked tag with a prefix, e.g. favourite('roof:'), if any is liked."""
        options = {t: v for t, v in self.tags.items() if t.startswith(prefix) and v > 0}
        return max(options, key=options.get) if options else None

    def remember(self, design):
        self.portfolio = sorted(self.portfolio + [design], key=lambda d: -d["score"])[:10]

    def save(self, path=None):
        path = path or self.path
        if not path:
            return
        with open(path, "w") as f:
            json.dump({"weights": self.weights, "tags": self.tags, "ratings": self.ratings,
                       "portfolio": self.portfolio}, f, indent=1)

    def load(self, path):
        with open(path) as f:
            data = json.load(f)
        self.weights.update({k: v for k, v in data.get("weights", {}).items() if k in DEFAULT_WEIGHTS})
        self.tags = data.get("tags", {})
        self.ratings = data.get("ratings", 0)
        self.portfolio = data.get("portfolio", [])
