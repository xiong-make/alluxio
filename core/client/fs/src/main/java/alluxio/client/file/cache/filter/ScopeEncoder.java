/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0 (the
 * "License"). You may not use this work except in compliance with the License, which is available
 * at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.client.file.cache.filter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A scope encoder that supports encode/decode scope information.
 */
public class ScopeEncoder {
  private final int mBitsPerScope;
  private final int mMaxNumScopes;
  private final ConcurrentHashMap<ScopeInfo, Integer> mScopeToId;
  private final ConcurrentHashMap<Integer, ScopeInfo> mIdToScope;
  private final Lock mLock;
  private int mCount; // the next scope id

  /**
   * Create a scope encoder.
   *
   * @param bitsPerScope the number of bits the scope has
   */
  public ScopeEncoder(int bitsPerScope) {
    mBitsPerScope = bitsPerScope;
    mMaxNumScopes = (1 << bitsPerScope);
    mCount = 0;
    mScopeToId = new ConcurrentHashMap<>();
    mIdToScope = new ConcurrentHashMap<>();
    mLock = new ReentrantLock();
  }

  /**
   * Encode scope information into integer.
   *
   * @param scopeInfo the scope will be encoded
   * @return the encoded scope
   */
  public int encode(ScopeInfo scopeInfo) {
    if (!mScopeToId.containsKey(scopeInfo)) {
      mLock.lock();
      if (!mScopeToId.containsKey(scopeInfo)) {
        mScopeToId.put(scopeInfo, mCount);
        mIdToScope.put(mCount, scopeInfo);
        mCount++;
      }
      mLock.unlock();
    }
    return mScopeToId.get(scopeInfo);
  }

  /**
   * Decode scope information from integer.
   *
   * @param id the encoded scope id will be decoded
   * @return the decoded scope information
   */
  public ScopeInfo decode(int id) {
    return mIdToScope.get(id);
  }
}
