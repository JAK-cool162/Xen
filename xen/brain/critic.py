"""Temporal-difference critics.

The same machinery learns two different things:
  * the striatum learns how much *reward* each action leads to;
  * the amygdala learns how much *harm* each action leads to (fear).

Both use a dueling head (a value for the situation plus an advantage per
action), which makes it much easier to learn how actions differ when most of
them lead to similar futures.
"""
import numpy as np

from .nn import MLP, huber_grad


class Critic:
    def __init__(self, obs_dim, n_actions, hidden=(256, 256), lr=3e-4,
                 gamma=0.97, tau=0.01, seed=0):
        self.n_actions = n_actions
        self.net = MLP([obs_dim, *hidden, n_actions + 1], lr=lr, seed=seed)
        self.target = self.net.copy()
        self.gamma = gamma
        self.tau = tau

    @staticmethod
    def _q(out):
        value, advantage = out[:, :1], out[:, 1:]
        return value + advantage - advantage.mean(1, keepdims=True)

    def values(self, obs):
        return self._q(self.net.predict(np.atleast_2d(obs)))

    def learn(self, obs, actions, signal, next_obs, done, next_actions, steps=None):
        """One TD step: Q(s,a) <- signal + gamma^steps * Q_target(s', a')."""
        rows = np.arange(len(actions))
        discount = self.gamma ** (1.0 if steps is None else steps)
        q_next = self._q(self.target.predict(next_obs))[rows, next_actions]
        y = signal + discount * (1.0 - done) * q_next
        out = self.net.forward(obs)
        q = self._q(out)
        diff = q[rows, actions] - y
        g = huber_grad(diff) / len(actions)
        grad = np.zeros_like(out)
        grad[:, 0] = g                                   # dQ/dV = 1
        grad[:, 1:] = -g[:, None] / self.n_actions       # dQ/dA_j = [j == a] - 1/n
        grad[rows, actions + 1] += g
        self.net.backward(grad)
        self.target.soft_update(self.net, self.tau)
        return float(np.abs(diff).mean())
