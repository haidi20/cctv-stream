//! Fixed-capacity ring buffer with no allocation after construction.

/// Pre-allocated FIFO ring buffer.
///
/// The backing storage never reallocates; pushing into a full buffer either
/// fails ([`RingBuffer::try_push`]) or evicts the oldest item
/// ([`RingBuffer::push_overwrite`]).
#[derive(Debug)]
pub struct RingBuffer<T> {
    slots: Box<[Option<T>]>,
    head: usize,
    len: usize,
}

impl<T> RingBuffer<T> {
    /// Create a buffer that can hold `capacity` items.
    ///
    /// # Panics
    ///
    /// Panics when `capacity` is zero; a zero-capacity ring buffer cannot make
    /// progress and would silently drop every frame.
    pub fn new(capacity: usize) -> Self {
        assert!(
            capacity > 0,
            "ring buffer capacity must be greater than zero"
        );

        let mut slots = Vec::with_capacity(capacity);
        slots.resize_with(capacity, || None);
        Self {
            slots: slots.into_boxed_slice(),
            head: 0,
            len: 0,
        }
    }

    /// Number of slots, fixed for the lifetime of the buffer.
    pub fn capacity(&self) -> usize {
        self.slots.len()
    }

    /// Number of items currently stored.
    pub fn len(&self) -> usize {
        self.len
    }

    /// True when no item is stored.
    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// True when no further item fits without eviction.
    pub fn is_full(&self) -> bool {
        self.len == self.capacity()
    }

    /// Push an item, returning it back when the buffer is full.
    pub fn try_push(&mut self, item: T) -> Result<(), T> {
        if self.is_full() {
            return Err(item);
        }

        self.slots[self.head] = Some(item);
        self.head = (self.head + 1) % self.capacity();
        self.len += 1;
        Ok(())
    }

    /// Push an item, evicting and returning the oldest when full.
    pub fn push_overwrite(&mut self, item: T) -> Option<T> {
        if self.is_full() {
            let evicted = self.slots[self.head].replace(item);
            self.head = (self.head + 1) % self.capacity();
            evicted
        } else {
            let _ = self.try_push(item);
            None
        }
    }

    /// Remove and return the oldest item.
    pub fn try_pop(&mut self) -> Option<T> {
        if self.len == 0 {
            return None;
        }

        let index = (self.head + self.capacity() - self.len) % self.capacity();
        let item = self.slots[index].take();
        self.len -= 1;
        item
    }

    /// Remove every item.
    pub fn clear(&mut self) {
        for slot in self.slots.iter_mut() {
            *slot = None;
        }
        self.head = 0;
        self.len = 0;
    }

    /// Iterate from oldest to newest.
    pub fn iter(&self) -> impl Iterator<Item = &T> {
        let capacity = self.capacity();
        let len = self.len;
        let head = self.head;
        (0..len).filter_map(move |offset| {
            let index = (head + capacity - len + offset) % capacity;
            self.slots[index].as_ref()
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    #[should_panic(expected = "capacity must be greater than zero")]
    fn zero_capacity_panics() {
        let _ = RingBuffer::<u8>::new(0);
    }

    #[test]
    fn preserves_fifo_order() {
        let mut buffer = RingBuffer::new(3);
        assert!(buffer.try_push(1).is_ok());
        assert!(buffer.try_push(2).is_ok());
        assert_eq!(buffer.try_pop(), Some(1));
        assert_eq!(buffer.try_pop(), Some(2));
        assert_eq!(buffer.try_pop(), None);
    }

    #[test]
    fn try_push_returns_item_when_full() {
        let mut buffer = RingBuffer::new(1);
        assert!(buffer.try_push(1).is_ok());
        assert_eq!(buffer.try_push(2), Err(2));
    }

    #[test]
    fn overwrite_keeps_newest_items() {
        let mut buffer = RingBuffer::new(2);
        assert_eq!(buffer.push_overwrite(1), None);
        assert_eq!(buffer.push_overwrite(2), None);
        assert_eq!(buffer.push_overwrite(3), Some(1));
        let items: Vec<_> = buffer.iter().copied().collect();
        assert_eq!(items, vec![2, 3]);
    }
}
