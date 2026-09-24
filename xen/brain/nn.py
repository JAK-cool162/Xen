"""A small, dependency-free neural network (numpy only) trained with Adam.

All parameters live in one flat float32 buffer (layers are views into it), so
optimiser steps and target-network updates are a handful of vector operations.
"""
import numpy as np


class MLP:
    """Fully connected ReLU network with a linear output layer."""

    def __init__(self, sizes, lr=3e-4, seed=0, max_grad_norm=10.0):
        rng = np.random.default_rng(seed)
        self.sizes = tuple(int(s) for s in sizes)
        self.lr = lr
        self.max_grad_norm = max_grad_norm
        shapes = []
        for fan_in, fan_out in zip(self.sizes[:-1], self.sizes[1:]):
            shapes += [(fan_in, fan_out), (fan_out,)]
        self._shapes = shapes
        total = sum(int(np.prod(s)) for s in shapes)
        self.flat = np.zeros(total, np.float32)
        self._grad = np.zeros(total, np.float32)
        self._m = np.zeros(total, np.float32)
        self._v = np.zeros(total, np.float32)
        self._tmp = np.zeros(total, np.float32)
        self._t = 0
        self._cache = None
        self._bind()
        last = len(self.sizes) - 2
        for i in range(len(self.sizes) - 1):
            fan_in = self.sizes[i]
            scale = np.sqrt(2.0 / fan_in) * (0.1 if i == last else 1.0)
            self.params[2 * i][...] = rng.standard_normal(shapes[2 * i]) * scale

    def _bind(self):
        """(Re)create the per-layer views into the flat buffers."""
        self.params, self._grads = [], []
        offset = 0
        for shape in self._shapes:
            n = int(np.prod(shape))
            self.params.append(self.flat[offset:offset + n].reshape(shape))
            self._grads.append(self._grad[offset:offset + n].reshape(shape))
            offset += n

    def predict(self, x):
        """Inference only (nothing is remembered for backprop)."""
        return self._run(x, keep=False)

    def forward(self, x):
        """Forward pass that remembers activations for the next backward()."""
        return self._run(x, keep=True)

    def _run(self, x, keep):
        h = np.asarray(x, np.float32)
        cache = [h]
        n = len(self.params) // 2
        for i in range(n):
            h = h @ self.params[2 * i] + self.params[2 * i + 1]
            if i < n - 1:
                np.maximum(h, 0.0, out=h)
            cache.append(h)
        if keep:
            self._cache = cache
        return h

    def gradients(self, grad_out):
        """Gradients w.r.t. the parameters (as layer views), given dLoss/dOutput."""
        cache = self._cache
        d = np.asarray(grad_out, np.float32)
        for i in reversed(range(len(self.params) // 2)):
            np.matmul(cache[i].T, d, out=self._grads[2 * i])
            np.sum(d, axis=0, out=self._grads[2 * i + 1])
            if i > 0:
                d = (d @ self.params[2 * i].T) * (cache[i] > 0)
        return self._grads

    def backward(self, grad_out):
        """Backprop dLoss/dOutput of the last forward() and take an Adam step."""
        self.gradients(grad_out)
        g, m, v, tmp = self._grad, self._m, self._v, self._tmp
        norm = float(np.sqrt(np.dot(g, g)))
        if norm > self.max_grad_norm:
            g *= self.max_grad_norm / norm
        self._t += 1
        b1, b2 = 0.9, 0.999
        m *= b1
        np.multiply(g, 1 - b1, out=tmp)
        m += tmp
        v *= b2
        np.multiply(g, g, out=tmp)
        tmp *= 1 - b2
        v += tmp
        np.sqrt(v, out=tmp)
        tmp *= 1.0 / np.sqrt(1 - b2 ** self._t)
        tmp += 1e-8
        np.divide(m, tmp, out=tmp)
        tmp *= self.lr / (1 - b1 ** self._t)
        self.flat -= tmp
        return norm

    def copy(self):
        clone = MLP.__new__(MLP)
        clone.__dict__.update(self.__dict__)
        for name in ("flat", "_grad", "_m", "_v", "_tmp"):
            setattr(clone, name, getattr(self, name).copy())
        clone._cache = None
        clone._bind()
        return clone

    def soft_update(self, source, tau):
        """Move parameters a fraction tau of the way towards source's."""
        np.subtract(source.flat, self.flat, out=self._tmp)
        self._tmp *= tau
        self.flat += self._tmp

    def state(self, prefix):
        return {f"{prefix}.{i}": p for i, p in enumerate(self.params)}

    def load_state(self, state, prefix):
        for i, p in enumerate(self.params):
            value = np.asarray(state[f"{prefix}.{i}"], np.float32)
            if value.shape != p.shape:
                raise ValueError(f"{prefix}.{i}: expected shape {p.shape}, got {value.shape}")
            p[...] = value
        self._m[:] = 0
        self._v[:] = 0
        self._t = 0


def huber_grad(diff, delta=1.0):
    """Derivative of the Huber loss: robust to occasional huge TD errors."""
    return np.clip(diff, -delta, delta)
