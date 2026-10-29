// RC-LocalLiveTranslate: read-only SenseVoice language evidence adapter.
// The values are model logits, not calibrated language probabilities.
#ifndef RC_SENSEVOICE_LANGUAGE_SCORES_H_
#define RC_SENSEVOICE_LANGUAGE_SCORES_H_

#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace rc_localtranslate {

inline bool ReadSenseVoiceLanguageScores(
    const float *first_frame, size_t vocabulary_size,
    const std::array<int32_t, 5> &language_ids,
    std::array<float, 5> *scores) {
  if (!first_frame || !scores || vocabulary_size == 0) return false;
  for (size_t i = 0; i < language_ids.size(); ++i) {
    if (language_ids[i] < 0 ||
        static_cast<size_t>(language_ids[i]) >= vocabulary_size) return false;
    for (size_t j = 0; j < i; ++j) {
      if (language_ids[i] == language_ids[j]) return false;
    }
  }
  size_t winner = 0;
  for (size_t i = 0; i < vocabulary_size; ++i) {
    if (!std::isfinite(first_frame[i])) return false;
    if (first_frame[i] > first_frame[winner]) winner = i;
  }
  bool language_won = false;
  for (size_t i = 0; i < language_ids.size(); ++i) {
    if (static_cast<size_t>(language_ids[i]) == winner) language_won = true;
  }
  // ConvertSenseVoiceResult interprets the first emitted token as language.
  // If frame zero is blank, unsupported language, or text, do not invent evidence.
  if (!language_won) return false;
  for (size_t i = 0; i < language_ids.size(); ++i) {
    (*scores)[i] = first_frame[language_ids[i]];
  }
  return true;
}

}  // namespace rc_localtranslate

#endif  // RC_SENSEVOICE_LANGUAGE_SCORES_H_
