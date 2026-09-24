"""Xen's imagination: a learned model of how the world responds to actions.

It predicts the next observation, the reward and the harm of an action.  Its
prediction error is surprise (curiosity), and the thinking cortex uses it to
imagine the consequences of choices before making them.
"""
import numpy as np

from .nn import MLP, huber_grad


def _sigmoid(x):
    return 1.0 / (1.0 + np.exp(-np.clip(x, -30, 30)))


class WorldModel:
    def __init__(self, obs_dim, n_actions, hidden=(256, 256), lr=3e-4, seed=0):
        self.obs_dim = obs_dim
        self.n_actions = n_actions
        self.net = MLP([obs_dim + n_actions, *hidden, obs_dim + 3], lr=lr, seed=seed)
        self.updates = 0

    def _input(self, obs, actions):
        obs = np.atleast_2d(np.asarray(obs, np.float32))
        onehot = np.zeros((len(obs), self.n_actions), np.float32)
        onehot[np.arange(len(obs)), actions] = 1.0
        return np.concatenate([obs, onehot], 1)

    def imagine(self, obs, actions):
        """Predicted (next_obs, reward, harm, p_done) for a batch."""
        obs = np.atleast_2d(np.asarray(obs, np.float32))
        out = self.net.predict(self._input(obs, actions))
        d = self.obs_dim
        next_obs = np.clip(obs + out[:, :d], -1.0, 1.0)
        return next_obs, out[:, d], np.maximum(out[:, d + 1], 0.0), _sigmoid(out[:, d + 2])

    def surprise(self, obs, action, next_obs):
        """How unexpected a single transition was."""
        predicted, _, _, _ = self.imagine(obs, [action])
        return float(np.mean((predicted[0] - next_obs) ** 2))

    def learn(self, obs, actions, reward, harm, next_obs, done):
        """Train on a batch; returns each transition's surprise before learning."""
        out = self.net.forward(self._input(obs, actions))
        d, n = self.obs_dim, len(obs)
        err = out[:, :d] - (next_obs - obs)
        surprise = (err ** 2).mean(1)
        grad = np.empty_like(out)
        grad[:, :d] = 2.0 * err / (n * d) * 50.0
        grad[:, d] = huber_grad(out[:, d] - reward) / n
        grad[:, d + 1] = huber_grad(out[:, d + 1] - harm) / n
        grad[:, d + 2] = (_sigmoid(out[:, d + 2]) - done) / n
        self.net.backward(grad)
        self.updates += 1
        return surprise
