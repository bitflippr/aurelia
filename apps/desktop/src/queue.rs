use crate::track::Track;
use std::collections::{HashMap, HashSet};

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct QueueEntryId(u64);

impl QueueEntryId {
    pub fn raw(self) -> u64 {
        self.0
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct QueueEntry {
    pub id: QueueEntryId,
    pub track: Track,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum RepeatMode {
    #[default]
    Off,
    All,
    One,
}

impl RepeatMode {
    pub fn cycle(self) -> Self {
        match self {
            Self::Off => Self::All,
            Self::All => Self::One,
            Self::One => Self::Off,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum AdvanceReason {
    Manual,
    TrackFinished,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RemoveOutcome {
    NotFound,
    Removed,
    CurrentChanged(Option<QueueEntryId>),
}

/// An editable playback queue whose identity is independent of array position.
///
/// Jellyfin item IDs are not queue IDs: the same track may intentionally occur
/// more than once. Every occurrence gets a unique `QueueEntryId`, and the
/// current item is always tracked by that stable identity.
#[derive(Clone, Debug, Default)]
pub struct PlaybackQueue {
    entries: Vec<QueueEntry>,
    current: Option<QueueEntryId>,
    next_id: u64,
    play_next_tail: Option<QueueEntryId>,
    repeat_mode: RepeatMode,
    shuffle_enabled: bool,
    unshuffled_order: Vec<QueueEntryId>,
    shuffle_nonce: u64,
}

impl PlaybackQueue {
    pub fn entries(&self) -> &[QueueEntry] {
        &self.entries
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub fn current_id(&self) -> Option<QueueEntryId> {
        self.current
    }

    pub fn current_index(&self) -> Option<usize> {
        let current = self.current?;
        self.index_of(current)
    }

    pub fn current(&self) -> Option<&QueueEntry> {
        self.current_index()
            .and_then(|index| self.entries.get(index))
    }

    pub fn repeat_mode(&self) -> RepeatMode {
        self.repeat_mode
    }

    pub fn shuffle_enabled(&self) -> bool {
        self.shuffle_enabled
    }

    pub fn toggle_shuffle(&mut self) -> bool {
        if self.shuffle_enabled {
            self.restore_unshuffled_upcoming();
            self.shuffle_enabled = false;
            self.unshuffled_order.clear();
        } else {
            self.shuffle_enabled = true;
            self.shuffle_upcoming();
        }
        self.play_next_tail = None;
        self.shuffle_enabled
    }

    pub fn cycle_repeat_mode(&mut self) -> RepeatMode {
        self.repeat_mode = self.repeat_mode.cycle();
        self.repeat_mode
    }

    pub fn replace(&mut self, tracks: Vec<Track>, start_index: usize) -> Option<QueueEntryId> {
        self.clear();
        if tracks.is_empty() {
            return None;
        }

        self.entries = tracks
            .into_iter()
            .map(|track| QueueEntry {
                id: self.allocate_id(),
                track,
            })
            .collect();
        let index = start_index.min(self.entries.len() - 1);
        self.current = Some(self.entries[index].id);
        if self.shuffle_enabled {
            self.shuffle_upcoming();
        }
        self.current
    }

    pub fn clear(&mut self) {
        self.entries.clear();
        self.current = None;
        self.play_next_tail = None;
        self.unshuffled_order.clear();
    }

    pub fn select(&mut self, id: QueueEntryId) -> bool {
        if self.index_of(id).is_none() {
            return false;
        }
        self.current = Some(id);
        self.play_next_tail = None;
        true
    }

    pub fn add_to_end(&mut self, track: Track) -> QueueEntryId {
        let id = self.allocate_id();
        self.entries.push(QueueEntry { id, track });
        if self.shuffle_enabled {
            self.unshuffled_order.push(id);
        }
        if self.current.is_none() {
            self.current = Some(id);
        }
        id
    }

    /// Insert after all other pending "play next" requests.
    ///
    /// Calling this for A and then B produces `current, A, B, ...`, preserving
    /// the user's request order instead of reversing it.
    pub fn play_next(&mut self, track: Track) -> QueueEntryId {
        if self.current.is_none() {
            return self.add_to_end(track);
        }

        let current_index = self
            .current_index()
            .expect("current queue entry must exist");
        let insertion_anchor = self.play_next_tail.or(self.current);
        let insertion_index = self
            .play_next_tail
            .and_then(|id| self.index_of(id))
            .filter(|index| *index >= current_index)
            .map_or(current_index + 1, |index| index + 1);
        let id = self.allocate_id();
        self.entries
            .insert(insertion_index, QueueEntry { id, track });
        if self.shuffle_enabled {
            let canonical_index = insertion_anchor
                .and_then(|anchor| {
                    self.unshuffled_order
                        .iter()
                        .position(|candidate| *candidate == anchor)
                })
                .map_or(self.unshuffled_order.len(), |index| index + 1);
            self.unshuffled_order.insert(canonical_index, id);
        }
        self.play_next_tail = Some(id);
        id
    }

    pub fn move_entry(&mut self, id: QueueEntryId, target_index: usize) -> bool {
        let Some(source_index) = self.index_of(id) else {
            return false;
        };
        if self.entries.len() < 2 {
            return true;
        }

        let entry = self.entries.remove(source_index);
        let target_index = target_index.min(self.entries.len());
        self.entries.insert(target_index, entry);
        if self.shuffle_enabled {
            self.unshuffled_order = self.entries.iter().map(|entry| entry.id).collect();
        }
        self.play_next_tail = None;
        true
    }

    pub fn remove(&mut self, id: QueueEntryId) -> RemoveOutcome {
        let Some(index) = self.index_of(id) else {
            return RemoveOutcome::NotFound;
        };
        let was_current = self.current == Some(id);
        self.entries.remove(index);
        self.unshuffled_order.retain(|candidate| *candidate != id);
        self.play_next_tail = None;

        if !was_current {
            return RemoveOutcome::Removed;
        }

        self.current = if self.entries.is_empty() {
            None
        } else {
            Some(self.entries[index.min(self.entries.len() - 1)].id)
        };
        RemoveOutcome::CurrentChanged(self.current)
    }

    pub fn clear_upcoming(&mut self) -> usize {
        let Some(current_index) = self.current_index() else {
            let removed = self.entries.len();
            self.clear();
            return removed;
        };
        let keep = current_index + 1;
        let removed = self.entries.len().saturating_sub(keep);
        self.entries.truncate(keep);
        let surviving: HashSet<_> = self.entries.iter().map(|entry| entry.id).collect();
        self.unshuffled_order
            .retain(|candidate| surviving.contains(candidate));
        self.play_next_tail = None;
        removed
    }

    pub fn next_for(&self, reason: AdvanceReason) -> Option<&QueueEntry> {
        let current_index = self.current_index()?;
        if reason == AdvanceReason::TrackFinished && self.repeat_mode == RepeatMode::One {
            return self.entries.get(current_index);
        }
        if let Some(next) = self.entries.get(current_index + 1) {
            return Some(next);
        }
        (self.repeat_mode == RepeatMode::All)
            .then(|| self.entries.first())
            .flatten()
    }

    pub fn advance(&mut self, reason: AdvanceReason) -> Option<QueueEntryId> {
        let next = self.next_for(reason)?.id;
        self.current = Some(next);
        self.play_next_tail = None;
        Some(next)
    }

    pub fn previous(&mut self) -> Option<QueueEntryId> {
        let current_index = self.current_index()?;
        let previous_index = current_index.checked_sub(1)?;
        let previous = self.entries[previous_index].id;
        self.current = Some(previous);
        self.play_next_tail = None;
        Some(previous)
    }

    fn index_of(&self, id: QueueEntryId) -> Option<usize> {
        self.entries.iter().position(|entry| entry.id == id)
    }

    fn allocate_id(&mut self) -> QueueEntryId {
        loop {
            self.next_id = self.next_id.wrapping_add(1);
            let candidate = QueueEntryId(self.next_id);
            if self.index_of(candidate).is_none() {
                return candidate;
            }
        }
    }

    fn shuffle_upcoming(&mut self) {
        self.unshuffled_order = self.entries.iter().map(|entry| entry.id).collect();
        let Some(current_index) = self.current_index() else {
            return;
        };
        let upcoming = &mut self.entries[current_index + 1..];
        if upcoming.len() < 2 {
            return;
        }

        self.shuffle_nonce = self.shuffle_nonce.wrapping_add(1);
        let mut random = self.shuffle_nonce
            ^ self.current.map_or(0, QueueEntryId::raw)
            ^ (upcoming.len() as u64).rotate_left(17)
            ^ 0x9e37_79b9_7f4a_7c15;
        for index in (1..upcoming.len()).rev() {
            random ^= random << 13;
            random ^= random >> 7;
            random ^= random << 17;
            upcoming.swap(index, random as usize % (index + 1));
        }
    }

    fn restore_unshuffled_upcoming(&mut self) {
        let Some(current_index) = self.current_index() else {
            return;
        };
        let ranks: HashMap<_, _> = self
            .unshuffled_order
            .iter()
            .enumerate()
            .map(|(index, id)| (*id, index))
            .collect();
        self.entries[current_index + 1..]
            .sort_by_key(|entry| ranks.get(&entry.id).copied().unwrap_or(usize::MAX));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn track(id: &str) -> Track {
        Track {
            id: id.into(),
            title: format!("Track {id}"),
            artist: "Artist".into(),
            album: "Album".into(),
            album_id: None,
            artwork_id: None,
            container: Some("flac".into()),
            duration_seconds: 180,
            art_color: 0,
        }
    }

    fn ids(queue: &PlaybackQueue) -> Vec<&str> {
        queue
            .entries()
            .iter()
            .map(|entry| entry.track.id.as_str())
            .collect()
    }

    #[test]
    fn duplicate_tracks_have_distinct_queue_identity() {
        let mut queue = PlaybackQueue::default();
        queue.replace(vec![track("same"), track("same")], 1);

        assert_ne!(queue.entries()[0].id, queue.entries()[1].id);
        assert_eq!(queue.current_id(), Some(queue.entries()[1].id));
        assert_eq!(queue.current_index(), Some(1));
    }

    #[test]
    fn play_next_preserves_request_order_and_add_to_end_appends() {
        let mut queue = PlaybackQueue::default();
        queue.replace(vec![track("current"), track("original")], 0);
        queue.play_next(track("first-next"));
        queue.play_next(track("second-next"));
        queue.add_to_end(track("last"));

        assert_eq!(
            ids(&queue),
            vec!["current", "first-next", "second-next", "original", "last"]
        );
    }

    #[test]
    fn moving_entries_never_changes_current_identity() {
        let mut queue = PlaybackQueue::default();
        queue.replace(vec![track("a"), track("b"), track("c")], 1);
        let current = queue.current_id().unwrap();

        assert!(queue.move_entry(current, 2));
        assert_eq!(queue.current_id(), Some(current));
        assert_eq!(queue.current_index(), Some(2));
        assert_eq!(queue.current().unwrap().track.id, "b");
    }

    #[test]
    fn removing_before_current_adjusts_only_the_derived_index() {
        let mut queue = PlaybackQueue::default();
        queue.replace(vec![track("a"), track("b"), track("c")], 2);
        let current = queue.current_id().unwrap();
        let first = queue.entries()[0].id;

        assert_eq!(queue.remove(first), RemoveOutcome::Removed);
        assert_eq!(queue.current_id(), Some(current));
        assert_eq!(queue.current_index(), Some(1));
        assert_eq!(queue.current().unwrap().track.id, "c");
    }

    #[test]
    fn removing_current_selects_next_then_previous_then_none() {
        let mut queue = PlaybackQueue::default();
        queue.replace(vec![track("a"), track("b"), track("c")], 1);

        let current = queue.current_id().unwrap();
        let outcome = queue.remove(current);
        assert_eq!(outcome, RemoveOutcome::CurrentChanged(queue.current_id()));
        assert_eq!(queue.current().unwrap().track.id, "c");

        let current = queue.current_id().unwrap();
        queue.remove(current);
        assert_eq!(queue.current().unwrap().track.id, "a");

        let current = queue.current_id().unwrap();
        assert_eq!(queue.remove(current), RemoveOutcome::CurrentChanged(None));
        assert!(queue.is_empty());
    }

    #[test]
    fn repeat_one_affects_track_completion_but_not_manual_next() {
        let mut queue = PlaybackQueue::default();
        queue.replace(vec![track("a"), track("b")], 0);
        queue.cycle_repeat_mode();
        queue.cycle_repeat_mode();
        assert_eq!(queue.repeat_mode(), RepeatMode::One);

        let current = queue.current_id();
        assert_eq!(queue.advance(AdvanceReason::TrackFinished), current);
        assert_eq!(
            queue.advance(AdvanceReason::Manual),
            Some(queue.entries()[1].id)
        );
    }

    #[test]
    fn repeat_all_wraps_at_the_end() {
        let mut queue = PlaybackQueue::default();
        queue.replace(vec![track("a"), track("b")], 1);
        queue.cycle_repeat_mode();

        assert_eq!(
            queue.advance(AdvanceReason::TrackFinished),
            Some(queue.entries()[0].id)
        );
    }

    #[test]
    fn clearing_upcoming_keeps_history_and_current() {
        let mut queue = PlaybackQueue::default();
        queue.replace(vec![track("a"), track("b"), track("c")], 1);
        let current = queue.current_id();

        assert_eq!(queue.clear_upcoming(), 1);
        assert_eq!(ids(&queue), vec!["a", "b"]);
        assert_eq!(queue.current_id(), current);
    }

    #[test]
    fn shuffle_keeps_history_and_current_then_restores_upcoming_order() {
        let mut queue = PlaybackQueue::default();
        queue.replace(
            vec![track("a"), track("b"), track("c"), track("d"), track("e")],
            1,
        );
        let current = queue.current_id();

        assert!(queue.toggle_shuffle());
        assert_eq!(queue.current_id(), current);
        assert_eq!(&ids(&queue)[..2], &["a", "b"]);
        let mut shuffled_upcoming = ids(&queue)[2..].to_vec();
        shuffled_upcoming.sort_unstable();
        assert_eq!(shuffled_upcoming, vec!["c", "d", "e"]);

        assert!(!queue.toggle_shuffle());
        assert_eq!(ids(&queue), vec!["a", "b", "c", "d", "e"]);
        assert_eq!(queue.current_id(), current);
    }

    #[test]
    fn disabling_shuffle_does_not_rewrite_played_history() {
        let mut queue = PlaybackQueue::default();
        queue.replace(
            vec![track("a"), track("b"), track("c"), track("d"), track("e")],
            0,
        );
        queue.toggle_shuffle();
        queue.advance(AdvanceReason::Manual);
        let history: Vec<_> = ids(&queue)[..=queue.current_index().unwrap()]
            .iter()
            .map(|id| (*id).to_string())
            .collect();

        queue.toggle_shuffle();

        assert_eq!(
            &ids(&queue)[..=queue.current_index().unwrap()],
            history.iter().map(String::as_str).collect::<Vec<_>>()
        );
        let upcoming = &ids(&queue)[queue.current_index().unwrap() + 1..];
        assert!(upcoming.windows(2).all(|pair| pair[0] < pair[1]));
    }

    #[test]
    fn play_next_remains_next_after_shuffle_is_disabled() {
        let mut queue = PlaybackQueue::default();
        queue.replace(vec![track("a"), track("b"), track("c"), track("d")], 0);
        queue.toggle_shuffle();
        queue.play_next(track("urgent-1"));
        queue.play_next(track("urgent-2"));

        queue.toggle_shuffle();

        assert_eq!(&ids(&queue)[..3], &["a", "urgent-1", "urgent-2"]);
    }
}
