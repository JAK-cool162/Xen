import os
import random
import sys
import unittest

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.join(ROOT, "scripts", "xentalk"))

try:
    import torch  # noqa: F401
    import train
except ImportError:                                     # (training needs torch; the mod doesn't)
    train = None
import teach


@unittest.skipUnless(train, "torch isn't installed")
class XenTalkPiecesTest(unittest.TestCase):
    def test_words_and_letters(self):
        v = train.build_vocab(["hey steve, going mining", "hey kai, going home", "me=rexa|tone=shy"], 400)
        text = "hey xXDarkXx, going mining 12!"
        self.assertEqual(v.decode(v.encode(text)), text.lower())          # (a name it's never seen: letter by letter)

    def test_thin_keeps_what_the_reply_uses(self):
        facts = "me=rexa|tone=shy|mood=calm|has=3 iron, no food|saw=a cave mouth|dream=find diamonds|heard=hi"
        rng = random.Random(1)
        for _ in range(50):
            t = train.thin(facts, "got 3 iron lol", rng)
            self.assertIn("has=3 iron", t)
            self.assertIn("heard=hi", t)

    def test_no_spelling(self):
        v = train.build_vocab(["hey steve, i'm going mining", "hey kai, i'm going home"], 400)
        after = lambda text: train.penalise(torch.zeros(len(v.items)), v.encode(text), v)
        self.assertEqual(float(after("hey")[v.index["m"]]), float("-inf"))     # (no letter straight after a word)
        self.assertEqual(float(after("hey")[v.index[" going"]]), 0.0)
        v.tails = train.contractions([{"reply": "i'm going, don't wait"}])
        self.assertEqual(v.tails, {"don": ["t"], "i": ["m"]})
        self.assertEqual(float(after("hey i'")[v.index["s"]]), float("-inf"))   # ("i's": not an ending it had)
        self.assertEqual(float(after("hey i'")[v.index["m"]]), 0.0)             # (the "m" of "i'm" is fine)
        self.assertEqual(float(after("hey i'")[v.index[" going"]]), float("-inf"))   # ("i' going")

    def test_names_out(self):
        facts, reply = train.masked("me=Meelu|tone=bold|friend=bfnuf|to=Alex|trust=friend|heard=yo meelu, where's bfnuf? alexa?",
                                    "hey alex, bfnuf went mining")
        self.assertEqual(facts, "me=xme|tone=bold|friend=xfriend|to=xto|trust=friend|heard=yo xme, where's xfriend? alexa?")
        self.assertEqual(reply, "hey xto, xfriend went mining")              # (a longer word with the name in it stays)


class TeacherFilterTest(unittest.TestCase):
    def test_grounded(self):
        f = {"has": "a stone pickaxe, 3 iron", "heard": "u got any coal"}
        self.assertTrue(teach.grounded("got 3 iron, no coal tho", f))
        self.assertFalse(teach.grounded("found 2 diamonds!!", f))
        self.assertIsNone(teach.ok("As an AI, I can't do that.", f))
        self.assertEqual(teach.ok("nah just 3 iron", f), "nah just 3 iron")


if __name__ == "__main__":
    unittest.main()
