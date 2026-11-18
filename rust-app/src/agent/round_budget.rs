//! The frozen user ceiling counts logical model rounds, not physical retries,
//! compression requests or an additional result-only summary.
pub(crate) struct ToolRoundBudget {
    limit: Option<usize>,
    used: usize,
}

impl ToolRoundBudget {
    pub(crate) fn new(configured: u32) -> Self {
        Self {
            limit: (configured > 0).then_some(configured as usize),
            used: 0,
        }
    }

    pub(crate) fn exhausted(&self) -> bool {
        self.limit.is_some_and(|limit| self.used >= limit)
    }

    pub(crate) fn begin_model_round(&mut self) -> bool {
        if self.exhausted() {
            return false;
        }
        self.used = self.used.saturating_add(1);
        true
    }
}

#[cfg(test)]
mod tests {
    use super::ToolRoundBudget;

    #[test]
    fn configured_one_stops_before_the_second_model_round() {
        let mut rounds = ToolRoundBudget::new(1);
        assert!(rounds.begin_model_round());
        assert!(rounds.exhausted());
        assert!(!rounds.begin_model_round());
    }

    #[test]
    fn configured_forty_allows_forty_rounds_instead_of_sixteen() {
        let mut rounds = ToolRoundBudget::new(40);
        for round in 0..40 {
            assert!(
                rounds.begin_model_round(),
                "rejected model round {}",
                round + 1
            );
        }
        assert!(rounds.exhausted());
        assert!(!rounds.begin_model_round());
    }

    #[test]
    fn configured_zero_has_no_arbitrary_round_ceiling() {
        let mut rounds = ToolRoundBudget::new(0);
        for round in 0..256 {
            assert!(
                rounds.begin_model_round(),
                "rejected model round {}",
                round + 1
            );
        }
        assert!(!rounds.exhausted());
    }
}
